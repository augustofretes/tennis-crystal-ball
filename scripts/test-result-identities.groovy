import groovy.json.JsonSlurper
import groovy.sql.Sql
import java.sql.Connection

// Runs the actual planner with SQL fixtures; no database or source network needed.
class IdentityFixtureSql extends Sql {
    List playerRows=[], aliases=[], tournaments=[], matches=[]
    IdentityFixtureSql() { super([isClosed:{false}] as Connection) }
    List rows(String query) {
        if(query.contains('FROM player p LEFT JOIN player_mapping'))return playerRows
        if(query=='SELECT alias,name FROM player_alias')return aliases
        if(query.contains('FROM tournament_mapping'))return tournaments
        if(query.contains('FROM pg_enum'))return ['AL','WC','Q','LL','SE','PR'].collect{[enumlabel:it]}
        throw new AssertionError('Unexpected query: '+query)
    }
    List rows(String query,List params) {
        assert query.contains('FROM match m JOIN tournament_event')
        matches.collect{new LinkedHashMap(it)}
    }
}

def loader=new GroovyClassLoader(getClass().classLoader)
loader.parseClass(new File('scripts/sync-recent-results.groovy'))
def Sync=loader.loadClass('RecentResultsSync')
File cache=new File('work/identity-tests');cache.mkdirs()
def sync=Sync.newInstance([SYNC_CACHE_DIR:cache.absolutePath])
def player={int id,String name,String dob='2000-01-01',String country='ITA' ->
    [player_id:id,name:name,dob:dob,country_id:country,ext_player_id:id+100000]
}
def sql=new IdentityFixtureSql()
sql.playerRows=[player(52898,'Harry Thursfield','2000-12-30'),player(54581,'Matteo Arnaldi','2001-02-22'),
    player(53856,'Luciano Darderi'),player(52132,'Lorenzo Musetti'),player(26006,'Taylor Fritz'),player(6057,'Bernard Tomic')]
sql.tournaments=[['337',1,'Vienna',32,'A'],['5014',2,'Shanghai Masters',96,'M'],['496',3,'Lyon',28,'B'],['328',4,'Basel',32,'A']].collect{v->
    [ext_tournament_id:v[0],tournament_id:v[1],name:v[2],draw_size:v[3],level:v[4],surface:'H',indoor:true]
}
List observed=[
    [200105,1,'R32',53856,52898,'7-5 6-2','Luciano Darderi','Matteo Arnaldi'],
    [199942,2,'R128',52898,6057,'6-3 6-1','Matteo Arnaldi','Bernard Tomic'],
    [199977,2,'R64',26006,52898,'7-5 6-4','Taylor Fritz','Matteo Arnaldi'],
    [200021,3,'R32',52132,52898,'6-1 6-4','Lorenzo Musetti','Matteo Arnaldi']
]
sql.matches=observed.collect{v->[match_id:v[0],tournament_id:v[1],season:2026,round:v[2],winner_id:v[3],loser_id:v[4],score:v[5],complete_stats:true]}
def sourceMatch={String key,Map t,String round,String winner,String loser,String score ->
    [key:key,extTournament:t.ext_tournament_id,season:2026,tournament:t.name,round:round,taLevel:t.level,
     winner:winner,loser:loser,score:Sync.score(score),wstats:[:],lstats:[:],datePrecision:'match']
}
List source=observed.collect{v->sourceMatch('source:'+v[0],sql.tournaments.find{it.tournament_id==v[1]},v[2],v[6],v[7],v[5])}
// Match counts reflect the reported database bracket counts. Filler participants
// and the 17 legitimate inserts are synthetic; this is not a production dry run.
[1:23,2:95,3:27,4:22].each {t,count->
    int missing=count-sql.matches.count{it.tournament_id==t}
    missing.times{i->sql.matches<<[match_id:300000+t*1000+i,tournament_id:t,season:2026,round:'R32',winner_id:400000+t*1000+i*2,loser_id:400001+t*1000+i*2,score:'6-0 6-0',complete_stats:true]}
}
17.times {i->
    int wid=600000+i*2,lid=wid+1
    sql.playerRows.addAll([player(wid,'Winner '+i),player(lid,'Loser '+i)])
    source<<sourceMatch('legitimate:'+i,sql.tournaments.find{it.tournament_id==(i<8?1:4)},'QF','Winner '+i,'Loser '+i,'6-2 6-2')
}
Map identities=sql.playerRows.findAll{it.player_id!=52898}.collectEntries{p->
    [(Sync.norm(p.name)):[name:p.name,extId:p.ext_player_id,dob:p.dob,country:p.country_id]]
}
def data={ -> [from:'2026-10-08',through:'2026-11-02',identities:identities.collectEntries{k,v->[(k):new LinkedHashMap(v)]},
              matches:source.collect{new LinkedHashMap(it)},conflicts:[],espnMissing:[]] }
Map corrupt=sync.plan(sql,data())
assert corrupt.inserts.size()==17
assert corrupt.rejects.size()==4
assert corrupt.rejects.every{it.reason=='Existing match identity conflict'}
assert corrupt.rejects.collectMany{it.matchIds}.sort()==observed.collect{it[0]}.sort()
try { sync.apply(sql,data(),corrupt);assert false:'Conflicting plan was applied' }
catch(IllegalStateException e) { assert e.message.startsWith('Import validation failed') }

sql.matches.each {m->
    if(m.match_id in observed.collect{it[0]}) {
        if(m.winner_id==52898)m.winner_id=54581
        if(m.loser_id==52898)m.loser_id=54581
    }
}
Map corrected=sync.plan(sql,data())
assert corrected.inserts.size()==17 && corrected.duplicates.size()==4
assert !corrected.rejects && !corrected.newPlayers
assert !sync.plan(sql,data()).rejects // Replanning is stable.

// External IDs, including reserved ESPN IDs, never override conflicting names.
def arnaldi=sql.playerRows.find{it.player_id==54581}
def thursfield=sql.playerRows.find{it.player_id==52898}
def mappingConflict=data()
mappingConflict.identities.matteoarnaldi.extId=thursfield.ext_player_id
Map mismatch=sync.plan(sql,mappingConflict)
assert mismatch.rejects.any{it.reason=='Player identity conflict' && it.fields.contains('name') && it.stored.id==52898}
assert !mismatch.inserts.any{it.wplayer.id==52898 || it.lplayer.id==52898}
['dob':'1999-01-01','country':'GBR'].each {field,value->
    Map d=data();d.identities.matteoarnaldi[field]=value
    assert sync.plan(sql,d).rejects.any{it.reason=='Player identity conflict' && field in it.fields}
}
assert !Sync.identityConflicts([name:'Félix Auger-Aliassime'],[id:1,name:'Felix Auger Aliassime'],[:])
assert !Sync.identityConflicts([name:'Wu Yibing'],[id:1,name:'Yibing Wu'],[:])
assert !Sync.identityConflicts([name:'Player Alias'],[id:1,name:'Canonical Player'],[playeralias:[[id:1]]])

// ESPN biography matching by DOB/country must also agree with the player's name.
def biographyData=data()
biographyData.identities.matteoarnaldi=[name:'Matteo Arnaldi',espnId:'test']
sql.playerRows.remove(arnaldi)
thursfield.dob='2001-02-22'
sync.metaClass.get={String url->assert url.endsWith('/test');'{"displayName":"Matteo Arnaldi","dateOfBirth":"2001-02-22T00:00Z","flag":{"alt":"ITA"}}'}
assert sync.plan(sql,biographyData).rejects.any{it.reason=='Player identity conflict' && 'name' in it.fields}
sql.playerRows<<arnaldi
thursfield.dob='2000-12-30'

// The bracket guard still rejects excess unrelated inserts.
Map tooMany=data()
4.times{i->tooMany.matches<<sourceMatch('overflow:'+i,sql.tournaments[0],'SF','Winner '+i,'Loser '+i,'6-2 6-2')}
assert sync.plan(sql,tooMany).rejects.any{it.reason=='Knockout bracket exceeds draw size'}
assert !sql.aliases // No global alias between the two players.
println 'RESULT_IDENTITY_TESTS_PASSED'
