package org.strangeforest.tcb.stats.model;

import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.*;

import org.junit.jupiter.api.*;
import org.springframework.dao.*;
import org.springframework.jdbc.core.*;
import org.springframework.test.util.*;
import org.strangeforest.tcb.stats.model.elo.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EloRatingsManagerTest {

	private static final LocalDate FROM = LocalDate.of(2025, 1, 6);
	private JdbcTemplate jdbc;
	private EloRatingsManager manager;

	@BeforeEach
	void setup() throws Exception {
		var dataSource = mock(DataSource.class);
		var connection = mock(Connection.class);
		var statement = mock(PreparedStatement.class);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.getAutoCommit()).thenReturn(true);
		when(connection.prepareStatement(anyString())).thenReturn(statement);
		when(statement.executeQuery()).thenReturn(mock(ResultSet.class));
		manager = new EloRatingsManager(dataSource);
		jdbc = mock(JdbcTemplate.class);
		ReflectionTestUtils.setField(manager, "jdbcTemplate", jdbc);
		when(jdbc.queryForList(contains("DISTINCT rank_date"), eq(LocalDate.class)))
			.thenReturn(List.of(FROM.minusWeeks(1), FROM));
		when(jdbc.queryForObject(contains("max(rank_date)"), eq(LocalDate.class)))
			.thenReturn(FROM.minusDays(1));
		doAnswer(call -> {
			RowCallbackHandler rows = call.getArgument(2);
			for (var i = 0; i < 20; i++)
				rows.processRow(match(i, i < 10 ? FROM.minusWeeks(1) : FROM));
			return null;
		}).when(jdbc).query(contains("SELECT m.match_id"), any(PreparedStatementSetter.class), any(RowCallbackHandler.class));
	}

	@Test
	void historicalRefreshReplacesOnlyDatesFromTheRequestedBoundary() throws Exception {
		manager.compute(true, false, FROM, 1);
		var writes = inOrder(jdbc);
		writes.verify(jdbc).update(contains("DELETE FROM player_elo_ranking"), any(PreparedStatementSetter.class));
		writes.verify(jdbc).batchUpdate(contains("INSERT INTO player_elo_ranking"), any(BatchPreparedStatementSetter.class));
		verify(jdbc, times(1)).update(contains("DELETE FROM player_elo_ranking"), any(PreparedStatementSetter.class));
		var prepared = mock(PreparedStatement.class);
		var deletion = org.mockito.ArgumentCaptor.forClass(PreparedStatementSetter.class);
		verify(jdbc).update(contains("DELETE FROM player_elo_ranking"), deletion.capture());
		deletion.getValue().setValues(prepared);
		verify(prepared).setObject(1, FROM, Types.DATE);
	}

	@Test
	void appendOnlyRefreshDoesNotDeleteExistingDates() throws Exception {
		manager.compute(true, false, null, 1);
		verify(jdbc, never()).update(contains("DELETE FROM player_elo_ranking"), any(PreparedStatementSetter.class));
		verify(jdbc).batchUpdate(contains("INSERT INTO player_elo_ranking"), any(BatchPreparedStatementSetter.class));
	}

	@Test
	void asynchronousSaveFailureFailsTheRefresh() {
		var failure = new DataIntegrityViolationException("save failed");
		doThrow(failure).when(jdbc).batchUpdate(contains("INSERT INTO player_elo_ranking"), any(BatchPreparedStatementSetter.class));
		assertThatThrownBy(() -> manager.compute(true, false, FROM, 1))
			.isInstanceOf(IllegalStateException.class).hasCause(failure);
	}

	private static ResultSet match(int id, LocalDate date) throws SQLException {
		var row = mock(ResultSet.class);
		when(row.getObject("end_date", LocalDate.class)).thenReturn(date);
		when(row.getInt(anyString())).thenAnswer(call -> {
			switch ((String)call.getArgument(0)) {
				case "match_id": return id;
				case "winner_id": return 1;
				case "loser_id": return 2;
				case "w_sets": return 2;
				case "w_games": return 12;
				case "l_games": return 8;
				default: return 0;
			}
		});
		when(row.getString("level")).thenReturn("A");
		when(row.getString("surface")).thenReturn("H");
		when(row.getString("round")).thenReturn("R32");
		when(row.getShort("best_of")).thenReturn((short)3);
		return row;
	}
}
