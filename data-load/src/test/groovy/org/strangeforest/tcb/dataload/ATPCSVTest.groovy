package org.strangeforest.tcb.dataload

import java.sql.*
import java.nio.file.*
import java.util.concurrent.ExecutionException

import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.*
import static org.mockito.Mockito.*
import static org.strangeforest.tcb.dataload.LoadParams.*

class ATPCSVTest {

	@TempDir public Path directory
	private final Map<String, String> saved = [:]

	@BeforeEach
	void configure() {
		[BASE_DIR_PROPERTY, FULL_LOAD_PROPERTY, FROM_SEASON_PROPERTY, TO_SEASON_PROPERTY].each {
			saved[it] = System.getProperty(it)
			System.clearProperty(it)
		}
		System.setProperty(BASE_DIR_PROPERTY, directory.toString())
	}

	@AfterEach
	void restore() {
		saved.each { key, value -> value == null ? System.clearProperty(key) : System.setProperty(key, value) }
	}

	@Test
	void discoversFutureSeasonsWithoutIncludingQualifyingOrDoubles() {
		['1968', '2024', '2025', '2031', 'qual_chall_2025', 'doubles_2025'].each {
			file("atp_matches_${it}.csv", '')
		}
		assert new ATPTennisLoader().matchFiles()*.name == ['1968', '2024', '2025', '2031'].collect { "atp_matches_${it}.csv" }
		System.setProperty(FROM_SEASON_PROPERTY, '2025')
		assert new ATPTennisLoader().matchFiles()*.name == ['atp_matches_2025.csv', 'atp_matches_2031.csv']
		System.setProperty(TO_SEASON_PROPERTY, '2024')
		assertThrows(IllegalArgumentException) { new ATPTennisLoader().matchFiles() }
	}

	@Test
	void deltaUsesLatestAvailableSeasonsRatherThanSystemClock() {
		['2023', '2024', '2025'].each { file("atp_matches_${it}.csv", '') }
		System.setProperty(FULL_LOAD_PROPERTY, 'false')
		assert new ATPTennisLoader().matchFiles()*.name == ['atp_matches_2024.csv', 'atp_matches_2025.csv']
	}

	@Test
	void missingDatasetFailsClearly() {
		assertThrows(IllegalArgumentException) { new ATPTennisLoader().matchFiles() }
		assertThrows(IllegalArgumentException) { new ATPTennisLoader().loadRankings(new FileRecorder()) }
	}

	@Test
	void rankingArchivesAreDiscoveredAndCurrentLoadedLast() {
		['current', '20s', '70s', '30s', '00s'].each { file("atp_rankings_${it}.csv", '') }
		def recorder = new FileRecorder()
		new ATPTennisLoader().loadRankings(recorder)
		assert recorder.files == ['70s', '00s', '20s', '30s', 'current'].collect { "atp_rankings_${it}.csv" }
	}

	@Test
	void modernPlayerHeadersAndLegacyHeaderlessPlayersBothLoadFirstRow() {
		def loader = new CapturingLoader(new PlayerLoader(null))
		def modern = file('players.csv', '\uFEFFplayer_id,name_first,name_last,hand,dob,ioc,height,wikidata_id\n200001,Test,Player,R,20010101,ESP,185,Q1\n')
		assert loader.loadFile(modern.path, true) == 1
		assert loader.rows[0].ext_player_id == 200001
		assert loader.rows[0].first_name == 'Test'
		assert loader.rows[0].country_id == 'ESP'
		loader.rows.clear()
		def legacy = file('legacy.csv', '200002,Legacy,Player,L,20000101,USA\n')
		assert loader.loadFile(legacy.path, true) == 1
		assert loader.rows[0].ext_player_id == 200002
	}

	@Test
	void modernRankingHeadersCanBeReorderedAndLegacyArchivesKeepFirstRow() {
		def loader = new CapturingLoader(new RankingLoader(null))
		def modern = file('rankings.csv', 'rank,ranking_date,player,points\n1,20250106,200001,10000\n')
		assert loader.loadFile(modern.path, true) == 1
		assert loader.rows[0] == [Date.valueOf('2025-01-06'), 200001, 1, 10000]
		loader.rows.clear()
		assert loader.loadFile(file('legacy.csv', '20241230,2,200002,9000\n').path, true) == 1
		assert loader.rows[0][1] == 200002
	}

	@Test
	void recentTournamentCategoriesAreMapped() {
		assert MatchLoader.mapEntry('W') == 'WC'
		assert MatchLoader.mapEntry('L') == 'LL'
		['ITF', 'UP', 'NG'].each { assert MatchLoader.mapEntry(it) == it }
		assert MatchLoader.mapLevel('A', 'United Cup', 2025, '9900') == 'T'
		assert MatchLoader.mapLevel('O', 'Paris Olympics', 2024, 'Olympics') == 'O'
		assert MatchLoader.mapLevel('N', 'Next Gen Finals', 2025, '7696') == 'H'
		['Dallas', 'Doha', 'Munich'].each {
			assert MatchLoader.mapLevel('A', it, 2024, '999') == 'B'
			assert MatchLoader.mapLevel('A', it, 2025, '999') == 'A'
		}
	}

	@Test
	void post2024MatchFixturePreservesPlayersScoreAndSeason() {
		def loader = new CapturingLoader(new MatchLoader(null))
		def fixture = new File(getClass().getResource('/atp-csv/atp_matches_2025.csv').toURI())
		assert loader.loadFile(fixture.path) == 1
		def match = loader.rows[0]
		assert match.season == 2025
		assert match.ext_winner_id == 200001
		assert match.ext_loser_id == 200002
		assert match.score == '6-4 6-3'
		assert match.w_sets == 2
		assert match.l_sets == 0
	}

	@Test
	void mirrorPlayerCountryCodesAreSupported() {
		['GDR', 'BIR', 'LEB'].each { assert BaseCSVLoader.country(it) == it }
		assert BaseCSVLoader.country('?', '???') == '???'
	}

	@Test
	void asynchronousDatabaseFailuresFailTheImport() {
		def pool = mockPool()
		def loader = new FailingLoader(pool)
		def ex = assertThrows(ExecutionException) {
			loader.loadFile(file('rows.csv', 'one,two\n1,2\n').path)
		}
		assert ex.cause instanceof SQLException
	}

	@groovy.transform.CompileStatic
	private static SqlPool mockPool() {
		SqlPool pool = mock(SqlPool)
		when(pool.getMetaClass()).thenReturn(GroovySystem.metaClassRegistry.getMetaClass(SqlPool))
		when(pool.size()).thenReturn(2)
		when(pool.withSql(any(Closure))).thenAnswer { invocation -> ((Closure)invocation.getArgument(0)).call([connection: null]) }
		pool
	}

	private File file(String name, String content) {
		def result = directory.resolve(name).toFile()
		result.setText(content, 'UTF-8')
		result
	}

	static class FileRecorder {
		List files = []
		int loadFile(String path, boolean first = false) { files << new File(path).name; 0 }
	}

	static class CapturingLoader extends BaseCSVLoader {
		BaseCSVLoader delegate
		List rows = []
		CapturingLoader(BaseCSVLoader delegate) { super(null); this.delegate = delegate }
		List columnNames() { delegate.columnNames() }
		String loadSql() { delegate.loadSql() }
		int batchSize() { delegate.batchSize() }
		def params(record, Connection conn) { delegate.params(record, conn) }
		def load(Iterator data) {
			def conn = [createArrayOf: { String type, Object[] values -> null }] as Connection
			data.each { rows << params(it, conn) }
			rows.size()
		}
	}

	static class FailingLoader extends BaseCSVLoader {
		FailingLoader(SqlPool pool) { super(pool) }
		String loadSql() { 'unused' }
		int batchSize() { 1 }
		def params(record, Connection conn) { [:] + [value: record.one] }
		def executeWithBatch(String sql, Collection<Map> batch) { throw new SQLException('test failure') }
	}
}
