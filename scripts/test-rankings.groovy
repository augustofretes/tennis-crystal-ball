import groovy.json.JsonOutput
import groovy.sql.Sql
import java.sql.Connection
import java.time.LocalDate

class RankingFixtureSql extends Sql {
    List players=[],rankings=[],writes=[],priorRankings=[]
    String latest='2026-06-08'
    boolean lock=true
    RankingFixtureSql(){super([isClosed:{false}] as Connection)}
    groovy.sql.GroovyRowResult firstRow(String query) {
        if(query.contains('pg_try_advisory_xact_lock'))return new groovy.sql.GroovyRowResult([acquired:lock])
        assert query=='SELECT max(rank_date)::text AS date FROM player_ranking'
        new groovy.sql.GroovyRowResult([date:latest])
    }
    List rows(String query) {assert query.contains('FROM player');players}
    List rows(String query,List params) {
        assert query.contains('WHERE rank_date')
        query.startsWith('SELECT player_id FROM')?priorRankings:rankings
    }
    groovy.sql.GroovyRowResult firstRow(String query,List params) {
        assert query.startsWith('INSERT INTO player(')
        int id=70000+players.size()
        players<<[player_id:id,name:params[0]+' '+params[1],dob:params[2],country:params[3]]
        new groovy.sql.GroovyRowResult([player_id:id])
    }
    List executeInsert(String query,List params) {
        writes<<params
        if(!rankings.any{it.player_id==params[1]})rankings<<[player_id:params[1],rank:params[2],rank_points:params[3]]
        []
    }
    boolean execute(String query){assert query.startsWith('REFRESH MATERIALIZED VIEW player_');true}
    void withTransaction(Closure work){work.call(this)}
}
def loader=new GroovyClassLoader(getClass().classLoader)
loader.parseClass(new File('scripts/sync-rankings.groovy'))
def Sync=loader.loadClass('WeeklyRankingsSync')
def sync=Sync.newInstance([:])
LocalDate today=LocalDate.now()
LocalDate monday=today.minusDays(today.dayOfWeek.value-1)
List rows=(1..500).collect{[rank:it,points:10000-it,name:'Player '+it,dob:'2000-01-01',country:'USA']}
def data={ -> def d=[date:monday.toString(),rows:rows.collect{new LinkedHashMap(it)}];d.sha256=Sync.digest(d);d }
def reject={Closure work -> boolean failed=false;try{work()}catch(IllegalStateException ignored){failed=true};assert failed }
Map points=[date:monday.toString(),rows:rows.collect{new LinkedHashMap(it)}]
Map identities=[date:monday.toString(),rows:rows.collect{new LinkedHashMap(it)}]
Map check=[date:monday.toString(),rows:rows.take(50).collect{[rank:it.rank,points:it.points,name:it.rank+' Player']}]
assert Sync.reconcile(points,identities,check).rows.size()==500
check.rows[0].points--;reject{Sync.reconcile(points,identities,check)};check.rows[0].points++
identities.date=monday.minusWeeks(1).toString();reject{Sync.reconcile(points,identities,check)};identities.date=points.date
identities.rows[12].name='Unrelated Person';reject{Sync.reconcile(points,identities,check)};identities.rows[12].name=rows[12].name
reject{Sync.validate(data()+[date:monday.minusWeeks(3).toString()])}
reject{Sync.validate(data()+[date:monday.plusWeeks(1).toString()])}
reject{Sync.validate(data()+[rows:rows.take(499)])}
def duplicate=data();duplicate.rows[3].name=duplicate.rows[2].name;reject{Sync.validate(duplicate)}
def invalid=data();invalid.rows[3].dob='';reject{Sync.validate(invalid)}
assert Sync.parsePoints('<select data-ranking-date><option selected value="2026-11-02"></option></select><section data-ranking-panel="world"><article><strong>#1</strong><a href="/atp/zverev/"><span class="block truncate">Alexander Zverev</span></a><strong>9,550<span>Points</span></strong></article></section>').rows[0].points==9550
assert Sync.parseIdentities('<p>Last update: 2026-11-02</p><table id="reportable"><tr><td>1</td><td>Alexander Zverev</td><td>GER</td><td>1997-04-20</td></tr></table>').rows[0].country=='GER'
assert Sync.parseCheck('<select><option value="2026-11-02" selected></option></select><table><tbody class="flags"><tr><td class="rank">1.</td><td class="t-name">Zverev Alexander</td><td class="long-point">9550</td></tr></tbody></table>').rows[0].name=='Zverev Alexander'
assert Sync.profile('<script type="application/ld+json">{"@graph":[{"mainEntity":{"@type":"Person","name":"Jack Kennedy","birthDate":"2008-06-04"}}]}</script>').dob=='2008-06-04'
assert Sync.norm('Matteo Arnaldi')!=Sync.norm('Harry Thursfield')
assert Sync.norm('Khumoun Sultanov')==Sync.norm('Khumoyun Sultanov')
def sql=new RankingFixtureSql()
sql.players=rows.collect{[player_id:it.rank,name:it.name,dob:it.dob,country:it.country]}
assert !sync.plan(sql,data()).rejects
sql.players[5].dob='1999-01-01';assert sync.plan(sql,data()).rejects.size()==1;reject{sync.apply(sql,data())};assert !sql.writes
sql.players[5].dob=null;assert !sync.plan(sql,data()).rejects
sql.players[5].dob='2000-01-01';sql.players[5].country='ITA';assert sync.plan(sql,data()).rejects.size()==1;sql.players[5].country='USA'
sql.players<<new LinkedHashMap(sql.players[5])+[player_id:999];assert sync.plan(sql,data()).rejects.size()==1;sql.players.remove(sql.players[-1])
sql.rankings=[[player_id:1,rank:2,rank_points:9999]];reject{sync.apply(sql,data())};assert !sql.writes
sql.rankings=[];sql.lock=false;reject{sync.apply(sql,data())};assert !sql.writes
sql.lock=true;sync.apply(sql,data());assert sql.rankings.size()==500
assert sync.plan(sql,data()).inserts==0
sql.latest=monday.plusWeeks(1).toString();reject{sync.plan(sql,data())}
def changed=data();changed.rows[0].points--;reject{sync.plan(new RankingFixtureSql(),changed)}
// Prefer a known birthdate to an incomplete duplicate, and otherwise retain
// the already-ranked profile only when that candidate is unique.
def fixture={ -> def db=new RankingFixtureSql();db.players=rows.collect{[player_id:it.rank,name:it.name,dob:it.dob,country:it.country]};db }
def duplicates=fixture()
duplicates.players<<[player_id:999,name:'Player 6',dob:'2000-01-01',country:'USA']
assert sync.plan(duplicates,data()).rejects.size()==1
duplicates.priorRankings=[[player_id:999]]
assert sync.plan(duplicates,data()).rows.find{it.rank==6}.playerId==999
duplicates.priorRankings=[[player_id:6],[player_id:999]]
assert sync.plan(duplicates,data()).rejects.size()==1
duplicates.players[-1].dob=null
def preferred=sync.plan(duplicates,data());assert !preferred.rejects && preferred.rows.find{it.rank==6}.playerId==6

Sync.IDENTITIES.bindings.each { b ->
    def db=fixture(), d=data()
    d.rows[5].putAll([name:b.name,dob:b.sourceDob,country:b.sourceCountry,verifiedProfile:[url:b.profile,dob:b.profileDob]])
    d.sha256=Sync.digest(d)
    db.players[5]=[player_id:b.playerId,name:b.name,dob:b.storedDob,country:b.storedCountry]
    def original=new LinkedHashMap(db.players[5])
    def approved=sync.plan(db,d)
    assert !approved.rejects && approved.rows.find{it.rank==6}.playerId==b.playerId
    assert db.players[5]==original // Bindings never repair profile fields.
    d.rows[5].remove('verifiedProfile');d.sha256=Sync.digest(d)
    assert sync.plan(db,d).rejects.size()==1
    d.rows[5].verifiedProfile=[url:b.profile,dob:b.profileDob];d.sha256=Sync.digest(d)
    db.players[5].dob='1970-01-01'
    assert sync.plan(db,d).rejects.size()==1 // Exact stored preconditions reject drift.
}
Sync.IDENTITIES.newPlayers.each { n ->
    def db=fixture(), d=data()
    d.rows[5].putAll([name:n.name,dob:n.dob,country:n.country,verifiedProfile:[url:n.profile,dob:n.dob]])
    d.sha256=Sync.digest(d)
    db.players[5]=[player_id:n.olderPlayerId,name:n.name,dob:n.olderDob,country:n.olderCountry]
    def older=new LinkedHashMap(db.players[5])
    def plan=sync.plan(db,d)
    assert !plan.rejects && plan.newPlayers.size()==1
    assert plan.rows.find{it.rank==6}.newPlayer
    d.rows[5].remove('verifiedProfile');d.sha256=Sync.digest(d)
    assert sync.plan(db,d).rejects.size()==1
    d.rows[5].verifiedProfile=[url:n.profile,dob:n.dob];d.sha256=Sync.digest(d)
    sync.apply(db,d)
    assert db.players[5]==older
    def current=db.players.find{it.name==n.name && it.dob==n.dob}
    assert current.player_id!=older.player_id
    assert !sync.plan(db,d).newPlayers && sync.plan(db,d).inserts==0
}
println 'RANKINGS_TESTS_PASSED'
