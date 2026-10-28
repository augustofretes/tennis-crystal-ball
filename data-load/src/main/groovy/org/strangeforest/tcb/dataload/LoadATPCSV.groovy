package org.strangeforest.tcb.dataload

// Local, repeatable ingestion without the legacy ATP Tour and Wikipedia scrapers.
def loader = new ATPTennisLoader()
loader.validateCSVFiles() // Validate the dataset and season range before modifying the database.
def sqlPool = new SqlPool(Math.max(2, SqlPool.connections))
try {
	loader.loadPlayers(new PlayerLoader(sqlPool))
	loader.loadRankings(new RankingLoader(sqlPool))
	loader.loadMatches(new MatchLoader(sqlPool))
}
finally {
	sqlPool.each { it.close() }
}
println 'ATP CSV ingestion complete. Recompute Elo ratings and refresh derived statistics separately.'
