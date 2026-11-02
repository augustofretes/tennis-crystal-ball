import groovy.sql.Sql
import java.time.LocalDate

// Run only after the reviewed identity repair. Does not collect or import results.
Map env=System.getenv()
String url='jdbc:postgresql://'+env.PGHOST+':'+(env.PGPORT?:'5432')+'/'+env.PGDATABASE+'?currentSchema=tcb,public'
def sql=Sql.newInstance(url,env.PGUSER,env.PGPASSWORD,'org.postgresql.Driver')
try {
    if(!sql.firstRow('SELECT pg_try_advisory_lock(7262601) AS acquired').acquired)throw new IllegalStateException('Another results sync is running')
    def repair=sql.firstRow("SELECT derived_completed FROM identity_repair_run WHERE repair_id='arnaldi-20261102'")
    if(!repair)throw new IllegalStateException('Identity repair has not been applied')
    if(repair.derived_completed) {println 'IDENTITY_DERIVED_ALREADY_COMPLETE';return}
    assert sql.firstRow('SELECT count(*) AS n FROM match WHERE winner_id=52898 OR loser_id=52898').n==0
    assert sql.firstRow('SELECT count(*) AS n FROM match WHERE winner_id=54581 OR loser_id=54581').n==165
    System.setProperty('tcb.db.url',url)
    System.setProperty('tcb.db.username',env.PGUSER)
    System.setProperty('tcb.db.password',env.PGPASSWORD)
    System.setProperty('tcb.data.full-load','false')
    System.setProperty('tcb.data.record-pause','0')
    println 'IDENTITY_DERIVED_START'
    def ds=org.strangeforest.tcb.dataload.SqlPool.connectionPoolDataSource(3)
    try {new org.strangeforest.tcb.stats.model.elo.EloRatingsManager(ds).compute(true,false,LocalDate.parse('2022-05-09'),2)}
    finally {ds.close()}
    def loader=new org.strangeforest.tcb.dataload.ATPTennisLoader()
    def pool=new org.strangeforest.tcb.dataload.SqlPool(1)
    try {
        pool.withSql {s->loader.refreshMaterializedViews(s)}
        pool.withSql {s->new org.strangeforest.tcb.dataload.RecordsLoader().loadRecords(loader,s)}
    } finally {pool.each{it.close()}}
    sql.execute("UPDATE identity_repair_run SET derived_completed=true WHERE repair_id='arnaldi-20261102'")
    println 'IDENTITY_DERIVED_COMPLETE'
} finally {sql.close()}
