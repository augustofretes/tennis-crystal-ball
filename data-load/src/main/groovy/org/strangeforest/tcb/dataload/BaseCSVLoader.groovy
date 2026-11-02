package org.strangeforest.tcb.dataload

import java.sql.*
import java.text.*
import java.util.concurrent.*

import org.strangeforest.tcb.util.*

import com.google.common.base.*
import com.xlson.groovycsv.*

import static org.strangeforest.tcb.dataload.LoaderUtil.*

abstract class BaseCSVLoader {

	protected final SqlPool sqlPool

	private static final String DEADLOCK_DETECTED = "40P01"

	BaseCSVLoader(SqlPool sqlPool) {
		this.sqlPool = sqlPool
	}

	List columnNames() { null }
	int threadCount() { Integer.MAX_VALUE }
	abstract String loadSql()
	abstract int batchSize()
	abstract def params(record, Connection conn)

	def loadFile(String file, boolean readFirstLine = false) {
		println "Loading file '$file'"
		def stopwatch = Stopwatch.createStarted()
		int rows
		new File(file).withReader('UTF-8') { reader ->
			reader.mark(65536)
			def header = reader.readLine()?.replace('\uFEFF', '')?.split(',')?.collect { it.replace('"', '').trim() }
			reader.reset()
			// Legacy files are positional and headerless. New Sackmann exports have
			// named headers, extra columns and sometimes a different column order.
			def aliases = [name_first: 'first_name', name_last: 'last_name', ioc: 'country',
				ranking_date: 'rank_date', player: 'player_id', points: 'rank_points']
			def names = columnNames()
			boolean hasHeader = header && header.any { it in ['player_id', 'ranking_date', 'rank_date', 'tourney_id', 'name_first'] }
			def csvParams = [readFirstLine: hasHeader ? false : readFirstLine]
			if (hasHeader)
				csvParams.columnNames = header.collect { aliases[it] ?: it }
			else if (names)
				csvParams.columnNames = names
			rows = load(CsvParser.parseCsv(csvParams, reader))
		}
		printLoadInfo(stopwatch, rows)
		return rows
	}

	def load(Iterable data) {
		def stopwatch = Stopwatch.createStarted()
		int rows = load(data.iterator())
		printLoadInfo(stopwatch, rows)
		return rows
	}

	static printLoadInfo(Stopwatch stopwatch, int rows) {
		println()
		stopwatch.stop()
		def seconds = stopwatch.elapsed(TimeUnit.SECONDS)
		int rowsPerSecond = seconds ? rows / seconds : 0
		println "Rows: $rows in $stopwatch ($rowsPerSecond row/s)"
	}

	def load(Iterator data) {
		def loadSql = loadSql()
		def batchSize = batchSize()
		def rows = 0
		def batches = new ProgressTicker('.' as char, 1).withDownstreamTicker(ProgressTicker.newLineTicker())
		def paramsBatch = []
		def futures = []
		if (sqlPool.size() < 2)
			throw new IllegalArgumentException("At least 2 DB connections are required for CVS data load, check ${SqlPool.DB_CONNECTIONS_PROPERTY}")
		sqlPool.withSql { sql ->
			def executor = Executors.newFixedThreadPool(Math.min(sqlPool.size(), threadCount()))
			def paramsConn = sql.connection
			try {
				for (record in data) {
					if (record.values.size() <= 1)
						continue
					try {
						def params = params(record, paramsConn)
						if (params) {
							paramsBatch << params
							if (++rows % batchSize == 0) {
								futures << execute(executor, loadSql, paramsBatch, batches)
								paramsBatch = []
							}
						}
					}
					catch (Exception ex) {
						throw new Exception("Error processing record $record", ex)
					}
				}
				if (paramsBatch)
					futures << execute(executor, loadSql, paramsBatch, batches)
				// Await every submitted batch so a database error cannot be reported as success.
				for (future in futures)
					future.get()
			}
			finally {
				executor.shutdown()
				executor.awaitTermination(1L, TimeUnit.DAYS)
			}
		}
		rows
	}

	def execute(ExecutorService executor, String loadSql, Collection<Map> paramsBatch, ProgressTicker batches) {
		executor.submit {
			executeWithBatch(loadSql, paramsBatch)
			batches.tick()
		}
	}

	def executeWithBatch(String loadSql, Collection<Map> paramsBatch) {
		try {
			sqlPool.withSql { sql ->
				sql.withBatch(loadSql) { ps ->
					paramsBatch.each { params -> ps.addBatch(params) }
				}
			}
		}
		catch (BatchUpdateException ex) {
			// withSql has rolled back and released the connection before retrying.
			if (ex.SQLState != DEADLOCK_DETECTED || paramsBatch.size() <= 1)
				throw ex
			print '*'
			for (paramsSubBatch in tile(paramsBatch))
				executeWithBatch(loadSql, paramsSubBatch)
		}
	}

	// Data conversion

	static String string(s, d = null) {
		s ?: d
	}

	static Integer integer(i) {
		i ? i.toInteger() : null
	}

	static Short smallint(i) {
		i ? i.toShort() : null
	}

	static BigDecimal decimal(d) {
		d ? d.toBigDecimal() : null
	}

	static BigDecimal safeDecimal(d) {
		try {
			d ? d.toBigDecimal() : null
		}
		catch (NumberFormatException ex) {
			null
		}
	}

	static Float real(f) {
		f ? f.toFloat() : null
	}

	static Date date(d) {
		if (d) {
			switch (d.length()) {
				case 4: d += '0701'; break
				case 6: d += '15'; break
			}
			new Date(new SimpleDateFormat('yyyyMMdd').parse(d).time)
		}
		else
			null
	}

	static Array shortArray(conn, a) {
		conn.createArrayOf('smallint', a)
	}

	static String country(c, d = null) {
		c && Country.code(c) ? c : d
	}

	static String hand(c) {
		switch (c) {
			case 'R': return 'R'
			case 'L': return 'L'
			default: return null
		}
	}

	static Object safeProperty(obj,  String propName) {
		obj.toMap()[propName]
	}
}
