import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.sql.Sql
import java.sql.Connection

/** SELECTs only. Proposed corrections affect returned maps in memory. */
class ArnaldiPreviewSql extends Sql {
    ArnaldiPreviewSql(Connection connection) { super(connection) }
    List rows(String query) {
        List result=super.rows(query)
        if(query.contains('FROM player p LEFT JOIN player_mapping')) {
            result.each { r ->
                if(r.ext_player_id==208286) {
                    r.player_id=54581;r.name='Matteo Arnaldi';r.dob='2001-02-22';r.country_id='ITA'
                } else if(r.player_id==52898)r.country_id='GBR'
            }
        }
        result
    }
    List rows(String query,List params) {
        List result=super.rows(query,params)
        if(query.contains('FROM match m JOIN tournament_event')) {
            result.each { r ->
                if(r.winner_id==52898)r.winner_id=54581
                if(r.loser_id==52898)r.loser_id=54581
            }
        }
        result
    }
}
def loader=new GroovyClassLoader(getClass().classLoader)
loader.parseClass(new File(args?args[0]:'scripts/sync-recent-results.groovy'))
def Sync=loader.loadClass('RecentResultsSync')
Map environment=new LinkedHashMap(System.getenv())
environment.SYNC_APPLY='false'
environment.SYNC_PROFILE_LIMIT='0'
def sync=Sync.newInstance(environment)
def sql=sync.database()
try {
    // JDBC's readOnly flag is only a hint while auto-commit is enabled.
    sql.execute('SET default_transaction_read_only=on')
    sql.connection.readOnly=true
    assert sql.firstRow('SHOW default_transaction_read_only').values().first()=='on'
    if(!sql.firstRow('SELECT pg_try_advisory_lock(7262601) AS acquired').acquired)throw new IllegalStateException('Another results sync is running')
    sql.rows('SELECT DISTINCT ON(tm.ext_tournament_id) tm.ext_tournament_id,t.name,t.level::text,t.surface::text,e.draw_size FROM tournament_mapping tm JOIN tournament t USING(tournament_id) LEFT JOIN tournament_event e USING(tournament_id) ORDER BY tm.ext_tournament_id,e.season DESC NULLS LAST').each{r->sync.tournamentDefaults[r.ext_tournament_id]=r as Map}
    Map data=sync.collect('2026-10-08','2026-11-02')
    def copy={new JsonSlurper().parseText(JsonOutput.toJson(data)) as Map}
    Map before=sync.plan(sql,copy())
    Sync.log('IDENTITY_LIVE_PLAN',[inserts:before.inserts.size(),duplicates:before.duplicates.size(),rejects:before.rejects,conflicts:before.sourceConflicts,missing:before.espnMissing.size()])
    Map after=sync.plan(new ArnaldiPreviewSql(sql.connection),copy())
    Set originalTournaments=['337','9410','328','7485','747','5014','496'] as Set
    List originalInserts=after.inserts.findAll{it.extTournament in originalTournaments}
    List additionalInserts=after.inserts.findAll{!(it.extTournament in originalTournaments)}
    Sync.log('IDENTITY_READ_ONLY_PREVIEW',[readOnly:true,corrections:'in-memory only',from:data.from,through:data.through,
        sourceMatches:data.matches.size(),inserts:after.inserts.size(),duplicates:after.duplicates.size(),rejects:after.rejects,
        originalRunInserts:originalInserts.size(),additionalInserts:additionalInserts.size(),
        conflicts:after.sourceConflicts,missing:after.espnMissing.size(),newPlayers:after.newPlayers,
        matches:after.inserts.collect{[key:it.key,date:it.date,tournament:it.tournament,round:it.round,winner:it.winner,loser:it.loser,score:it.score.text]}])
    assert !after.rejects && !after.sourceConflicts && !after.espnMissing
    assert originalInserts.size()==17
    println 'IDENTITY_PRODUCTION_READ_ONLY_PREVIEW_PASSED'
} finally {sql.close()}
