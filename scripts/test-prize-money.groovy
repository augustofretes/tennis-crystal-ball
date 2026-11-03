import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.sql.Sql
import java.sql.Connection
import java.time.Instant

class PrizeMoneyFixtureSql extends Sql {
    List players=[],writes=[]
    boolean lock=true
    int updateCount=1
    PrizeMoneyFixtureSql(){super([isClosed:{false}] as Connection)}
    List rows(String query){assert query.contains('FROM player');players}
    groovy.sql.GroovyRowResult firstRow(String query) {
        assert query.contains('pg_try_advisory_xact_lock')
        new groovy.sql.GroovyRowResult([acquired:lock])
    }
    int executeUpdate(String query,List params) {
        assert query.startsWith('UPDATE player SET prize_money=?')
        assert query.contains('prize_money IS NOT DISTINCT FROM ?')
        writes<<params
        updateCount
    }
    void withTransaction(Closure work){work.call(this)}
}
def loader=new GroovyClassLoader(getClass().classLoader)
loader.parseClass(new File('scripts/sync-prize-money.groovy'))
def Sync=loader.loadClass('PrizeMoneySync')
def sync=Sync.newInstance([:])
def reject={Closure work -> boolean failed=false;try{work()}catch(IllegalStateException ignored){failed=true};assert failed}
Map player=[playerId:52602,name:'Carlos Alcaraz Garfia',dob:'2003-05-05',
    wikipedia:'https://en.wikipedia.org/wiki/Carlos_Alcaraz_Garfia',previous:'$1,089,590']
def html={String money -> '''<h1 id="firstHeading">Carlos Alcaraz</h1><table class="infobox vcard">
<caption class="fn">Carlos Alcaraz</caption><tr><th>Full name</th><td>Carlos Alcaraz Garfia</td></tr>
<tr><th>Born</th><td><span class="bday">2003-05-05</span></td></tr>
<tr><th>Prize money</th><td>'''+money+'</td></tr></table>'}
Map source=Sync.parse(html('US $68,050,698<sup>[2][3]</sup><ul><li>6th all-time in earnings</li></ul>'),player)
assert source.prizeMoney=='$68,050,698' && source.amount==68050698L
['US$68,050,698','$68,050,698','USD 68,050,698','US&nbsp;$68,050,698'].each{
    assert Sync.parse(html(it),player).amount==68050698L
}
assert Sync.parse(html('US $68,050'),player).amount==68050
['€68,050,698','A$68,050,698','US $68,050,698 + $5,000',
 'US $68.5 million','US $68,050,698 (2025: $10,000)','US $6,80,50698',
 'US $0','US $1,000,000,001'].each{value ->
    reject{Sync.parse(html(value),player)}
}
reject{Sync.parse(html('$68,050,698'),player+[dob:'2003-05-06'])}
reject{Sync.parse(html('$68,050,698'),player+[name:'Albert Alcaraz Ivorra'])}
reject{Sync.parse(html('$68,050,698').replace('class="bday"',''),player)}
reject{Sync.parse(html('$68,050,698')+html('$1,000'),player)}
reject{Sync.parse(html('$68,050,698').replace('Prize money','Season prize money'),player)}
assert Sync.article(player.wikipedia)
['http://en.wikipedia.org/wiki/A','https://example.org/wiki/A','https://en.wikipedia.org/w?search=A',
 'https://en.wikipedia.org/wiki/Special:Search','https://en.wikipedia.org:443/wiki/A',
 'https://user@en.wikipedia.org/wiki/A'].each{assert !Sync.article(it)}

Map data=[fetchedAt:Instant.now().toString(),rows:[player+source],skipped:[]]
data.sha256=Sync.digest(data)
// JSON round trips must preserve the checksum of the reviewed snapshot.
data=new JsonSlurper().parseText(JsonOutput.toJson(data)) as Map
Sync.validate(data)
def sql=new PrizeMoneyFixtureSql()
sql.players=[[player_id:52602,name:player.name,dob:player.dob,wikipedia:player.wikipedia,prize_money:player.previous]]
assert sync.plan(sql,data).changes.size()==1
sync.apply(sql,data);assert sql.writes.size()==1 && sql.writes[0][0]=='$68,050,698'
sql.writes=[];sql.players[0].prize_money='$68,050,698'
sync.apply(sql,data);assert !sql.writes && !sync.plan(sql,data).changes
sql.players[0].prize_money='$2,000,000'
reject{sync.apply(sql,data)};assert !sql.writes
sql.players[0].prize_money=player.previous;sql.players[0].dob='1970-01-01'
reject{sync.apply(sql,data)};assert !sql.writes
sql.players[0].dob=player.dob;sql.lock=false
reject{sync.apply(sql,data)};assert !sql.writes
sql.lock=true;sql.updateCount=0
reject{sync.apply(sql,data)}
def changed=new JsonSlurper().parseText(JsonOutput.toJson(data)) as Map
changed.rows[0].amount=7;reject{Sync.validate(changed)}
changed.sha256=Sync.digest(changed);reject{Sync.validate(changed)}
changed=new JsonSlurper().parseText(JsonOutput.toJson(data)) as Map
changed.fetchedAt=Instant.now().minusSeconds(8*86400).toString();changed.sha256=Sync.digest(changed)
reject{Sync.validate(changed)}
changed.fetchedAt=Instant.now().plusSeconds(86400).toString();changed.sha256=Sync.digest(changed)
reject{Sync.validate(changed)}
changed=new JsonSlurper().parseText(JsonOutput.toJson(data)) as Map
changed.rows<<changed.rows[0];changed.sha256=Sync.digest(changed);reject{Sync.validate(changed)}
// Disabled means no network requests or database connections, even with APPLY set.
Sync.newInstance([PRIZE_MONEY_APPLY:'true']).run()
println 'PRIZE_MONEY_TESTS_PASSED'
