import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.sql.Sql
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.text.Normalizer
import java.util.concurrent.Executors

/** Completed ATP singles only. Source snapshots and validation precede any write. */
class RecentResultsSync {
    static final List ROUNDS = ['RR','R128','R64','R32','R16','QF','SF','BR','F']
    static final Set LEVELS = ['G','M','A','B','F','D','T','L'] as Set
    static final Set INDOOR = ['vienna','basel','almaty','brussels','stockholm','lyon','marseille','metz','paris masters','tour finals','next gen finals'] as Set
    static final Map ESPN_TOURNAMENTS=['414':'416','396':'322','942':'414','172':'520','49':'321','415':'440','27':'500','129':'311','444':'741','637':'8994','188':'540','306':'316','7':'314','22':'439','304':'319','400':'7290','424':'7480','888':'418','421':'421','718':'422','363':'6242','189':'560','441':'7581','1001':'4713','5':'329','959':'747','315':'5014','440':'7485','708':'9410','110':'496','452':'496','10':'337','23':'328']
    static String norm(Object s) {
        String n=Normalizer.normalize(s?.toString() ?: '', Normalizer.Form.NFD).replaceAll('\\p{M}','').toLowerCase(Locale.ROOT).replaceAll('[^a-z0-9]','')
        [carlosalcarazgarfia:'carlosalcaraz',danielmeridaaguilar:'danielmerida',zhangzhizhen:'zhizhenzhang',shangjuncheng:'junchengshang',buyunchaokete:'yunchaoketebu',sunfajing:'fajingsun',hujia:'jiahu',zhouyi:'yizhou',cuijie:'jiecui'][n] ?: n
    }
    static Integer num(Object s) { s == null || s.toString().trim() == '' ? null : Integer.valueOf(s.toString()) }
    static String iso(Object s) { String x=s.toString(); x.contains('-') ? x.take(10) : "${x[0..3]}-${x[4..5]}-${x[6..7]}" }
    static void log(String type, Object value) { println(type+' '+JsonOutput.toJson(value)) }
    File cache
    Map env
    long lastRequest=0
    boolean limited=false
    Map tournamentDefaults=[:]
    RecentResultsSync(Map environment=System.getenv()) {
        env=environment
        cache=new File(env.SYNC_CACHE_DIR ?: '/tmp/tennis-sync'); cache.mkdirs()
    }
    synchronized String get(String url) {
        String key=java.security.MessageDigest.getInstance('SHA-256').digest(url.bytes).encodeHex().toString()
        File f=new File(cache,key+'.txt')
        if(f.exists() && env.SYNC_REUSE_CACHE == 'true') return f.getText('UTF-8')
        if(env.SYNC_CACHE_ONLY=='true' || (limited && url.contains('tennisabstract.com'))) throw new IOException('Source cache miss; continuing with ESPN results')
        Exception failure
        for(int attempt=0;attempt<2;attempt++) {
            try {
                long delay=1200L-(System.currentTimeMillis()-lastRequest)
                if(delay>0) Thread.sleep(delay)
                lastRequest=System.currentTimeMillis()
                def request=Jsoup.connect(url).userAgent('Mozilla/5.0').timeout(30000).maxBodySize(20*1024*1024).ignoreContentType(true)
                String proxy=env.HTTPS_PROXY ?: env.HTTP_PROXY
                if(proxy) { def uri=new URI(proxy); request.proxy(uri.host,uri.port) }
                String body=request.execute().body()
                if(body.contains('<title>Just a moment') || body.contains('Sorry, you have been blocked')) throw new IOException('Challenge response')
                f.setText(body,'UTF-8'); return body
            } catch(Exception e) {
                failure=e
                if(e instanceof org.jsoup.HttpStatusException && e.statusCode==429) {limited=true;throw new IOException('Source rate limit reached; continuing with ESPN results',e)}
                if(attempt==0) Thread.sleep(1200)
            }
        }
        throw new IOException('Could not fetch '+url+': '+failure.message, failure)
    }
    static List parseTA(String html) {
        def matcher=html =~ /(?s)var matchmx\s*=\s*(\[.*?\]);/
        if(!matcher.find()) throw new IllegalArgumentException('Tennis Abstract match array missing')
        List rows=new JsonSlurper().parseText(matcher.group(1)) as List
        if(rows.any { !(it instanceof List) || it.size()<47 }) throw new IllegalArgumentException('Unexpected match array schema')
        rows
    }
    static Map profile(String html) {
        Map p=[:]
        ['fullname','country','dob','ht','hand','atp_id'].each { key ->
            def m=html =~ /(?m)var ${key}\s*=\s*['"]?([^;'"\r\n]*)/
            if(m.find()) p[key]=m.group(1).trim()
        }
        if(!p.fullname || !p.country) throw new IllegalArgumentException('Player identity missing')
        p
    }
    static Map stats(List r, boolean opponent) {
        List indices=opponent ? [30,31,32,33,34,35,36,37,38] : [21,22,23,24,25,26,27,28,29]
        Map out=[:]; ['ace','df','sv_pt','1st_in','1st_won','2nd_won','sv_gms','bp_sv','bp_fc'].eachWithIndex { k,i -> out[k]=num(r[indices[i]]) }
        if(out.sv_pt!=null && (out.sv_pt<0 || out['1st_in']>out.sv_pt || out['1st_won']>out['1st_in'] || out['2nd_won']>out.sv_pt-out['1st_in'] || out.bp_sv>out.bp_fc)) throw new IllegalArgumentException('Invalid raw serve counts')
        out
    }
    static Map score(String value) {
        String s=value.replace('W.O.','W/O').replace('W/O.','W/O').trim()
        String outcome=['RET','W/O','DEF','ABD'].find { s.contains(it) }
        List sets=[]
        def m=s =~ /(\d+)-(\d+)(?:\((\d+)\))?/
        while(m.find()) {
            int w=m.group(1).toInteger(), l=m.group(2).toInteger(); Integer tb=num(m.group(3))
            boolean tie=Math.max(w,l)==7 && Math.min(w,l)==6
            sets << [w:w,l:l,wt:tie&&tb!=null?(w>l?Math.max(7,tb+2):tb):null,lt:tie&&tb!=null?(l>w?Math.max(7,tb+2):tb):null,wb:tie&&w>l?1:0,lb:tie&&l>w?1:0]
        }
        if(!sets && !(outcome in ['W/O','ABD','DEF'])) throw new IllegalArgumentException('Unparseable score: '+s)
        int ws=sets.count { it.w>it.l && (it.w>=6 && it.w-it.l>=2 || it.w==7 && it.l==6 || it.w>=10 && it.w-it.l>=2) }
        int ls=sets.count { it.l>it.w && (it.l>=6 && it.l-it.w>=2 || it.l==7 && it.w==6 || it.l>=10 && it.l-it.w>=2) }
        if(!outcome && sets && Math.max(sets[-1].w,sets[-1].l)<6) {outcome='RET';s+=' RET'}
        if(!outcome && ws<=ls) throw new IllegalArgumentException('Score not in winner orientation: '+s)
        [text:s,outcome:outcome,sets:sets,ws:ws,ls:ls,wg:sets.sum(0){it.w},lg:sets.sum(0){it.l},wt:sets.sum(0){it.wb},lt:sets.sum(0){it.lb}]
    }
    static String pair(String a,String b) { [norm(a),norm(b)].sort().join('|') }
    Map collect(String from,String through) {
        String url='https://site.api.espn.com/apis/site/v2/sports/tennis/atp/scoreboard?dates='+from.replace('-','')+'-'+through.replace('-','')+'&limit=1000'
        Map espn=new JsonSlurper().parseText(get(url)) as Map
        if(!(espn.events instanceof List) || !espn.events) throw new IllegalStateException('Empty ESPN tournament inventory')
        Map eventTournaments=[:] + ESPN_TOURNAMENTS
        espn.events.each { e ->
            String id=e.id.split('-')[0]
            if(!eventTournaments[id]) {
                String label=norm(e.name+' '+(e.venue?.displayName?:'')).replace('atp','')
                List candidates=tournamentDefaults.entrySet().findAll { entry ->
                    String token=norm(entry.value.name).replace('masters','').replace('tour','').replace('atp','')
                    token.size()>3 && label.contains(token)
                }.sort{a,b->norm(b.value.name).size()<=>norm(a.value.name).size()}
                if(candidates && (candidates.size()==1 || norm(candidates[0].value.name).size()>norm(candidates[1].value.name).size()))eventTournaments[id]=candidates[0].key
            }
        }
        Map participants=[:], evidence=[:]; List esMatches=[]
        espn.events.each { e -> e.groupings?.findAll { it.grouping.slug=='mens-singles' }?.each { g ->
            g.competitions.each { c ->
                if(!c.status?.type?.completed || c.date.take(10)>through || c.round?.displayName?.startsWith('Qualifying')) return
                if(c.competitors?.size()!=2 || c.competitors.count{it.winner}!=1) return
                def w=c.competitors.find{it.winner}, l=c.competitors.find{!it.winner}
                if(!w.athlete?.displayName || !l.athlete?.displayName) return
                [w,l].each { participants[norm(it.athlete.displayName)]=it.athlete.displayName }
                Map m=[id:c.id,eventId:e.id,event:e.name,date:c.date.take(10),round:c.round.displayName,winner:w.athlete.displayName,loser:l.athlete.displayName,wsets:w.linescores*.value,lsets:l.linescores*.value,raw:c]
                esMatches << m; String key=pair(m.winner,m.loser)
                if(!evidence[key]) evidence[key]=[]
                if(!evidence[key].any{it.id==m.id}) evidence[key]<<m
            }
        }}
        String ranking=''
        try{ranking=get('https://tennisabstract.com/reports/atpRankings.html')}catch(IOException ignored){log('OPTIONAL_PLAYER_DIRECTORY_UNAVAILABLE',[:])}
        Map slugs=[:]
        Jsoup.parse(ranking).select('a[href*=player.cgi?p=]').each { a -> slugs[norm(a.text())]=a.attr('href').split('\\?p=')[1] }
        // The ranking report is only a directory of player-page identifiers, not imported ranking data.
        Map profiles=[:], allRows=[:], failures=[:]
        def pool=Executors.newFixedThreadPool(1)
        try {
            Map frequency=esMatches.collectMany{[norm(it.winner),norm(it.loser)]}.countBy{it}
            def selected=participants.entrySet().sort{a,b->(frequency[b.key]?:0)<=>(frequency[a.key]?:0)}.take((env.SYNC_PROFILE_LIMIT?:'80') as int)
            def futures=selected.collect { entry -> String key=entry.key,name=entry.value; pool.submit({
                Map renamed=[zhangzhizhen:'ZhizhenZhang',shangjuncheng:'JunchengShang',buyunchaokete:'YunchaoketeBu',sunfajing:'FajingSun',hujia:'JiaHu',zhouyi:'YiZhou',cuijie:'JieCui']
                String slug=renamed[key] ?: slugs[key] ?: Normalizer.normalize(name,Normalizer.Form.NFD).replaceAll('\\p{M}','').replaceAll('[^A-Za-z]','')
                try {
                    String u='https://www.tennisabstract.com/cgi-bin/player-classic.cgi?p='+java.net.URLEncoder.encode(slug,'UTF-8')
                    String h=get(u); Map p=profile(h); p.url=u
                    [key:key,profile:p,rows:parseTA(h)]
                } catch(Exception ex) {
                    [key:key,error:ex.message]
                }
            } as java.util.concurrent.Callable) }
            futures.eachWithIndex { f,i ->
                def x=f.get(); if(x.error) failures[x.key]=x.error
                else { profiles[x.key]=x.profile; allRows[x.key]=x.rows }
                if((i+1)%25==0) log('SOURCE_PROGRESS',[profiles:i+1,total:participants.size(),failures:failures.size()])
            }
        } finally { pool.shutdownNow() }
        Map external=[:], identities=[:]
        profiles.each { k,p -> identities[norm(p.fullname)]=[name:p.fullname,country:p.country,dob:p.dob?iso(p.dob):null,height:num(p.ht),hand:p.hand,url:p.url] }
        allRows.each { k,rows -> rows.each { r ->
            if(r.size()>47 && r[47]?.toString()?.matches('[0-9]{5,8}') && iso(r[0])>=from) {
                external[norm(r[11])]=num(r[47])
                identities.putIfAbsent(norm(r[11]),[name:r[11],country:r[18],dob:r[16]?iso(r[16]):null,height:num(r[17]),hand:r[15]])
            }
        }}
        identities.each { k,v -> v.extId=external[k] }
        Map matches=[:]; List conflicts=[]
        allRows.each { ownKey,rows ->
            Map p=profiles[ownKey]
            rows.each { r ->
                String date=iso(r[0])
                if(date<from || date>through || !(r[3] in LEVELS) || !(r[4] in ['W','L']) || !(r[8] in ROUNDS)) return
                def id=r[43] =~ /^(\d{4})-(.+)-(\d+)$/
                if(!id.matches()) {conflicts<<[id:r[43],reason:'Unrecognized tournament identifier'];return}
                String tid=id.group(2).replaceFirst('^0+(?!$)','')
                boolean ownWin=r[4]=='W'
                String wn=ownWin?p.fullname:r[11], ln=ownWin?r[11]:p.fullname
                List candidates=(evidence[pair(wn,ln)] ?: []).findAll { it.date>=date && it.date<=LocalDate.parse(date).plusDays(21).toString() && (!eventTournaments[it.eventId.split('-')[0]] || eventTournaments[it.eventId.split('-')[0]]==tid) }
                def exact=candidates.findAll { norm(it.winner)==norm(wn) }
                if(candidates && !exact) {conflicts<<[id:r[43],reason:'Sources disagree on winner',names:[wn,ln]];return}
                Map sc
                try { sc=score(r[9]) }catch(Exception ex){conflicts<<[id:r[43],reason:ex.message];return}
                if(exact.size()>1) exact=exact.findAll{it.wsets.collect{x->x as int}==sc.sets*.w && it.lsets.collect{x->x as int}==sc.sets*.l}
                Map es=exact.size()==1?exact[0]:null
                if(es && sc.outcome==null && (es.wsets.collect{it as int}!=sc.sets*.w || es.lsets.collect{it as int}!=sc.sets*.l)) {conflicts<<[id:r[43],reason:'Sources disagree on set score',ta:sc.text,espn:es];return}
                Map m=[key:r[43],extTournament:tid,season:num(id.group(1)),tournament:r[1].replaceFirst('^ATP ',''),tournamentDate:date,date:es?.date?:date,datePrecision:es?'match':'tournament',surface:[Hard:'H',Clay:'C',Grass:'G',Carpet:'P'][r[2]],taLevel:r[3],round:r[8],bestOf:num(r[10]),winner:wn,loser:ln,winnerSeed:num(ownWin?r[6]:r[13]),loserSeed:num(ownWin?r[13]:r[6]),winnerEntry:ownWin?r[7]:r[14],loserEntry:ownWin?r[14]:r[7],winnerRank:num(ownWin?r[5]:r[12]),loserRank:num(ownWin?r[12]:r[5]),score:sc,minutes:num(r[20]),wstats:stats(r,!ownWin),lstats:stats(r,ownWin),espnId:es?.id,espnIds:exact*.id,sourceUrls:[p.url],raw:r]
                if(!m.surface || !(m.bestOf in [3,5])) {conflicts<<[id:m.key,reason:'Invalid surface or match format'];return}
                Map prior=matches[m.key]
                if(prior && (norm(prior.winner)!=norm(m.winner) || prior.score.text!=m.score.text)) {conflicts<<[id:m.key,reason:'Player histories disagree'];return}
                if(prior) prior.sourceUrls.add(p.url)
                else matches[m.key]=m
            }
        }
        // ESPN is the result-coverage fallback; unavailable statistics stay null.
        Map templates=matches.values().groupBy{it.extTournament}.collectEntries{k,v->[(k):v[0]]}
        matches.values().each{m->(m.espnIds?:[]).each{eid->def es=esMatches.find{it.id==eid};if(es)eventTournaments[es.eventId.split('-')[0]]=m.extTournament}}
        espn.events.each { e ->
            String tid=eventTournaments[e.id.split('-')[0]]
            Map t=tournamentDefaults[tid]
            List main=e.groupings?.find{it.grouping.slug=='mens-singles'}?.competitions?.findAll{!it.round?.displayName?.startsWith('Qualifying')}?:[]
            if(!templates[tid] && t && main) {
                String date=main.collect{it.date.take(10)}.min()
                templates[tid]=[extTournament:tid,season:date.take(4).toInteger(),tournament:t.name,tournamentDate:date,surface:t.surface,taLevel:t.level,bestOf:t.level=='G'?5:3,defaultDraw:t.draw_size]
            }
        }
        Map seenEspn=matches.values().collectMany{it.espnIds?:[]}.collectEntries{[(it):true]}
        Map fallbackPairs=[:]
        esMatches.each { e ->
            if(seenEspn[e.id]) return
            String tid=eventTournaments[e.eventId.split('-')[0]]
            Map template=templates[tid]
            if(!template)return
            String dedup=tid+'|'+pair(e.winner,e.loser)+'|'+e.round
            if(fallbackPairs[dedup])return
            Map roundMap=['Final':'F','Semifinal':'SF','Quarterfinal':'QF']
            String rd=roundMap[e.round]
            if(!rd && e.round.startsWith('Round ')) {
                int first=matches.values().findAll{it.extTournament==tid && it.round.startsWith('R') && it.round!='RR'}.collect{num(it.round.substring(1))}.max()?:((template.defaultDraw?:32)>64?128:(template.defaultDraw?:32)>32?64:32)
                rd='R'+(first.intdiv(1 << (e.round.substring(6).toInteger()-1)))
            }
            if(!(rd in ROUNDS))return
            List w=e.raw.competitors.find{it.winner}.linescores?:[], l=e.raw.competitors.find{!it.winner}.linescores?:[]
            if(w.size()!=l.size())return
            String description=e.raw.status.type.description+' '+(e.raw.notes*.text).join(' ')
            String ending=description.toLowerCase().contains('retir')?' RET':description.toLowerCase().contains('walk')?' W/O':description.toLowerCase().contains('default')?' DEF':''
            String text=w.withIndex().collect{v,i->
                int a=v.value as int,b=l[i].value as int
                def tb=a>b?l[i].tiebreak:v.tiebreak
                a+'-'+b+(Math.max(a,b)==7 && Math.min(a,b)==6 && tb!=null?'('+tb+')':'')
            }.join(' ')+ending
            Map sc
            try{sc=score(text)}catch(Exception ignored){return}
            String ownKey='espn:'+e.eventId+':'+e.id
            Map m=new LinkedHashMap(template)
            m.putAll([key:ownKey,date:e.date,datePrecision:'match',round:rd,winner:e.winner,loser:e.loser,winnerSeed:num(e.raw.competitors.find{it.winner}.curatedRank?.current),loserSeed:num(e.raw.competitors.find{!it.winner}.curatedRank?.current),winnerEntry:null,loserEntry:null,winnerRank:null,loserRank:null,score:sc,minutes:null,wstats:[:],lstats:[:],espnId:e.id,espnIds:[e.id],sourceUrls:[url],raw:e.raw])
            matches[ownKey]=m;fallbackPairs[dedup]=true
            e.raw.competitors.each{c->
                String n=c.athlete.displayName
                identities.putIfAbsent(norm(n),[name:n,espnId:c.id])
                if(!identities[norm(n)].espnId)identities[norm(n)].espnId=c.id
            }
        }
        List matchedEspn=matches.values().collectMany{it.espnIds?:[]}.unique()
        List missing=esMatches.findAll{ !(it.id in matchedEspn) && it.date>=from }.unique{it.id}
        Map result=[from:from,through:through,collectedAt:java.time.Instant.now().toString(),profiles:profiles.size(),failedProfiles:failures,identities:identities,matches:matches.values().toList(),conflicts:conflicts,espnMissing:missing,espnTournaments:espn.events.collect{[id:it.id,name:it.name]}]
        new File(cache,'collection.json').setText(JsonOutput.toJson(result),'UTF-8')
        log('SOURCE_SUMMARY',[from:from,through:through,profiles:profiles.size(),failedProfiles:failures,uniqueMatches:matches.size(),espnMatched:matchedEspn.size(),espnMissing:missing.size(),conflicts:conflicts.size(),tournaments:result.matches.groupBy{it.extTournament}.collect{k,v->[id:k,name:v[0].tournament,matches:v.size(),last:v*.date.max()]}])
        result
    }
    Sql database() {
        Sql sql=Sql.newInstance('jdbc:postgresql://'+env.PGHOST+':'+(env.PGPORT?:'5432')+'/'+env.PGDATABASE+'?currentSchema=tcb,public',env.PGUSER,env.PGPASSWORD,'org.postgresql.Driver')
        sql.execute('SET search_path=tcb,public'); sql
    }
    Map plan(Sql sql,Map data) {
        Map byExt=[:], byName=[:], players=[:]
        sql.rows('SELECT p.player_id,full_name(p.first_name,p.last_name) AS name,p.country_id,p.dob::text AS dob,pm.ext_player_id FROM player p LEFT JOIN player_mapping pm USING(player_id)').each { r ->
            Map p=[id:r.player_id,name:r.name,country:r.country_id,dob:r.dob,extId:r.ext_player_id]
            players[r.player_id]=p
            if(r.ext_player_id)byExt[r.ext_player_id]=p
            String key=norm(r.name); if(!byName[key])byName[key]=[]
            if(!byName[key].any{it.id==p.id})byName[key]<<p
        }
        sql.rows('SELECT alias,name FROM player_alias').each { a -> if(byName[norm(a.name)])byName[norm(a.alias)]=byName[norm(a.name)] }
        Map resolved=[:], unknown=[:]
        data.identities.each { k,p ->
            def known=p.extId?byExt[p.extId]:null
            if(!known) {
                List choices=byName[norm(p.name)]?:[]
                if(p.dob) {def same=choices.findAll{it.dob==p.dob};if(same.size()==1)known=same[0]}
                if(!known && choices.size()==1)known=choices[0]
            }
            if(!known && !p.extId && p.espnId) {
                Map bio=new JsonSlurper().parseText(get('https://sports.core.api.espn.com/v2/sports/tennis/athletes/'+p.espnId)) as Map
                if(bio.dateOfBirth && bio.flag?.alt?.matches('[A-Z]{3}')) {
                    p.dob=bio.dateOfBirth.take(10);p.country=bio.flag.alt
                    List same=players.values().findAll{it.dob==p.dob && it.country==p.country}
                    if(same.size()==1)known=same[0]
                    else {p.extId=-(p.espnId as int);p.name=bio.displayName}
                }
            }
            if(known)resolved[norm(p.name)]=known
            else if(p.country && p.dob && p.extId) resolved[norm(p.name)]=[name:p.name,country:p.country,dob:p.dob,extId:p.extId,height:p.height,hand:p.hand,isNew:true]
            else unknown[norm(p.name)]=p
            if(resolved[norm(p.name)])resolved[k]=resolved[norm(p.name)]
        }
        Map tournaments=[:]
        sql.rows('SELECT DISTINCT ON (tm.ext_tournament_id) tm.ext_tournament_id,t.tournament_id,t.name,t.level::text,t.surface::text,t.indoor,e.draw_size FROM tournament_mapping tm JOIN tournament t USING(tournament_id) LEFT JOIN tournament_event e USING(tournament_id) ORDER BY tm.ext_tournament_id,e.season DESC NULLS LAST').each { r -> tournaments[r.ext_tournament_id]=r as Map }
        Map existing=[:]
        sql.rows('SELECT m.match_id,m.tournament_event_id,e.tournament_id,e.season,m.round::text,m.winner_id,m.loser_id,m.score,(s.w_sv_pt IS NOT NULL AND s.l_sv_pt IS NOT NULL) AS complete_stats FROM match m JOIN tournament_event e USING(tournament_event_id) LEFT JOIN match_stats s ON s.match_id=m.match_id AND s.set=0 WHERE e.season>=?', [data.from.take(4).toInteger()]).each { r -> existing[[r.tournament_id,r.season,r.round,[r.winner_id,r.loser_id].sort().join(':')].join('|')]=r }
        List inserts=[], rejects=[], duplicates=[], enrichments=[];Map pending=[:]
        data.matches.each { m ->
            Map t=tournaments[m.extTournament]
            if(m.taLevel=='D') {
                String group=m.tournament.split(' R')[0]
                t=tournaments.values().find{it.name==group}
            }
            Map w=resolved[norm(m.winner)], l=resolved[norm(m.loser)]
            if(!t || !w || !l) {rejects<<[key:m.key,tournament:m.tournament,winner:m.winner,loser:m.loser,reason:!t?'Unknown tournament':!w?'Unknown winner':'Unknown loser'];return}
            m.dbTournament=t; m.wplayer=w; m.lplayer=l
            def prior=(!w.isNew && !l.isNew)?existing[[t.tournament_id,m.season,m.round,[w.id,l.id].sort().join(':')].join('|')]:null
            if(prior) {
                if(prior.winner_id!=w.id) rejects<<[key:m.key,reason:'Winner conflicts with existing database',matchId:prior.match_id]
                else {
                    duplicates<<[key:m.key,matchId:prior.match_id]
                    if(!prior.complete_stats && m.wstats.sv_pt && m.lstats.sv_pt) {m.existingMatchId=prior.match_id;enrichments<<m}
                }
            }else {
                String identity=[t.tournament_id,m.season,m.round,[norm(m.winner),norm(m.loser)].sort().join(':')].join('|')
                Map other=pending[identity]
                if(other) {
                    if(norm(other.winner)!=norm(m.winner) || other.score.text!=m.score.text)rejects<<[key:m.key,reason:'Duplicate sources disagree',other:other.key]
                    else duplicates<<[key:m.key,otherKey:other.key]
                } else {inserts<<m;pending[identity]=m}
            }
        }
        List usedNew=inserts.collectMany{[it.wplayer,it.lplayer]}.findAll{it.isNew}.unique{it.extId}
        Map result=[inserts:inserts,rejects:rejects,duplicates:duplicates,enrichments:enrichments.unique{it.existingMatchId},newPlayers:usedNew,sourceConflicts:data.conflicts,espnMissing:data.espnMissing]
        Map report=[from:data.from,through:data.through,inserts:inserts.size(),duplicates:duplicates.size(),newPlayers:usedNew.collect{[name:it.name,extId:it.extId]},rejects:rejects,sourceConflicts:data.conflicts,espnMissing:data.espnMissing.size(),withRawStats:inserts.count{it.wstats.sv_pt && it.lstats.sv_pt},actualMatchDates:inserts.count{it.datePrecision=='match'},tournaments:inserts.groupBy{it.dbTournament.ext_tournament_id}.collect{k,v->[id:k,name:v[0].tournament,matches:v.size(),rounds:v.groupBy{it.round}.collectEntries{r,rows->[(r):rows.size()]}]}]
        new File(cache,'validation.json').setText(JsonOutput.prettyPrint(JsonOutput.toJson(report)),'UTF-8')
        report.statsEnrichment=result.enrichments.size()
        log('IMPORT_PLAN',report)
        result
    }
    static Map procedure(Sql sql,String name) {
        def row=sql.firstRow("SELECT p.proargnames,oidvectortypes(p.proargtypes) AS types FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='tcb' AND p.proname=? ORDER BY p.pronargs DESC LIMIT 1",[name])
        List names=row.proargnames.array as List, types=row.types.split(', ') as List
        [names:names,types:types,statement:'CALL '+name+'('+types.collect{'?::'+it}.join(',')+')']
    }
    static void call(Sql sql,Map proc,Map values) {
        List args=[]
        proc.names.eachWithIndex { n,i ->
            Object v=values[n]
            if(v instanceof GString)v=v.toString()
            if(v!=null && proc.types[i].endsWith('[]')) v=sql.connection.createArrayOf('int2',(v as List).toArray())
            args<<v
        }
        sql.execute(proc.statement,args)
    }
    Map apply(Sql sql,Map data,Map plan) {
        if(plan.rejects || plan.sourceConflicts) throw new IllegalStateException('Import validation failed; inspect validation.json before applying')
        if(plan.espnMissing) throw new IllegalStateException('ESPN results missing from the reconciled set; refusing an incomplete backfill')
        String runId=UUID.randomUUID().toString()
        sql.withTransaction {
            sql.execute('CREATE TABLE IF NOT EXISTS result_sync_run (run_id text PRIMARY KEY,started_at timestamptz NOT NULL DEFAULT now(),completed_at timestamptz,from_date date NOT NULL,through_date date NOT NULL,inserted integer NOT NULL DEFAULT 0,report jsonb NOT NULL,derived_completed boolean NOT NULL DEFAULT false)')
            sql.execute('CREATE TABLE IF NOT EXISTS result_sync_match (source_key text PRIMARY KEY,match_id bigint NOT NULL REFERENCES match(match_id),run_id text NOT NULL REFERENCES result_sync_run(run_id),date_precision text NOT NULL,source_urls jsonb NOT NULL,raw_record jsonb NOT NULL)')
            sql.execute('INSERT INTO result_sync_run(run_id,from_date,through_date,report) VALUES (?,?::date,?::date,?::jsonb)',[runId,data.from,data.through,JsonOutput.toJson([profiles:data.profiles,failedProfiles:data.failedProfiles,espnMatched:data.matches.count{it.espnId}])])
            plan.newPlayers.each { p ->
                List words=p.name.split(' ') as List
                sql.execute('CALL load_player(?::integer,?::text,?::text,?::date,?::text,?::text)',[p.extId,words.size()>1?words[0..-2].join(' '):null,words[-1],p.dob,p.country,p.hand in ['R','L']?p.hand:null])
                def row=sql.firstRow('SELECT p.player_id,full_name(first_name,last_name) AS name FROM player p JOIN player_mapping pm USING(player_id) WHERE pm.ext_player_id=?',[p.extId]); p.id=row.player_id;p.name=row.name
            }
            Map proc=procedure(sql,'load_match'), nextNumber=[:]
            List ordered=plan.inserts.sort { a,b -> a.date<=>b.date ?: ROUNDS.indexOf(a.round)<=>ROUNDS.indexOf(b.round) ?: a.key<=>b.key }
            ordered.each { m ->
                Map t=m.dbTournament
                String eventKey=t.tournament_id+'|'+m.season
                if(!nextNumber.containsKey(eventKey)) nextNumber[eventKey]=(sql.firstRow('SELECT coalesce(max(m.match_num),0)::integer AS n FROM match m JOIN tournament_event e USING(tournament_event_id) WHERE e.tournament_id=? AND e.season=?',[t.tournament_id,m.season]).n as int)+1
                int matchNum=nextNumber[eventKey]++
                String level=m.taLevel in ['G','M','F','D','T','L']?m.taLevel:t.level
                boolean indoor=m.surface=='H' && (INDOOR.contains(m.tournament.toLowerCase(Locale.ROOT)) || t.indoor)
                Map p=[p_ext_tournament_id:t.ext_tournament_id,p_season:m.season,p_tournament_date:m.tournamentDate,p_tournament_name:t.name,p_event_name:m.taLevel=='D'?t.name:m.tournament,p_tournament_level:level,p_surface:m.surface,p_indoor:indoor,p_draw_type:'KO',p_draw_size:t.draw_size,p_rank_points:[G:2000,M:1000,A:500,B:250][level],p_match_num:matchNum,p_date:m.date,p_round:m.round,p_best_of:m.bestOf,p_ext_winner_id:m.wplayer.extId,p_winner_name:m.wplayer.name,p_winner_country_id:m.wplayer.country,p_winner_seed:m.winnerSeed,p_winner_entry:m.winnerEntry?:null,p_winner_rank:m.winnerRank,p_ext_loser_id:m.lplayer.extId,p_loser_name:m.lplayer.name,p_loser_country_id:m.lplayer.country,p_loser_seed:m.loserSeed,p_loser_entry:m.loserEntry?:null,p_loser_rank:m.loserRank,p_score:m.score.text,p_outcome:m.score.outcome,p_w_sets:m.score.ws,p_l_sets:m.score.ls,p_w_games:m.score.wg,p_l_games:m.score.lg,p_w_tbs:m.score.wt,p_l_tbs:m.score.lt,p_w_set_games:m.score.sets*.w,p_l_set_games:m.score.sets*.l,p_w_set_tb_pt:m.score.sets*.wt,p_l_set_tb_pt:m.score.sets*.lt,p_w_set_tbs:m.score.sets*.wb,p_l_set_tbs:m.score.sets*.lb,p_minutes:m.minutes]
                m.wstats.each{k,v->p['p_w_'+k]=v};m.lstats.each{k,v->p['p_l_'+k]=v}
                call(sql,proc,p)
                def saved=sql.firstRow('SELECT m.match_id FROM match m JOIN tournament_event e USING(tournament_event_id) WHERE e.tournament_id=? AND e.season=? AND m.match_num=?',[t.tournament_id,m.season,matchNum])
                sql.execute('INSERT INTO result_sync_match(source_key,match_id,run_id,date_precision,source_urls,raw_record) VALUES (?,?,?,?,?::jsonb,?::jsonb)',[m.key,saved.match_id,runId,m.datePrecision,JsonOutput.toJson(m.sourceUrls+(m.espnId?['https://site.api.espn.com/apis/site/v2/sports/tennis/atp/scoreboard?dates='+m.date.replace('-','')]:[])),JsonOutput.toJson(m.raw)])
            }
            Map statsProc=procedure(sql,'load_match_stats')
            plan.enrichments.each { m ->
                Map p=[p_match_id:m.existingMatchId,p_minutes:m.minutes]
                m.wstats.each{k,v->p['p_w_'+k]=v};m.lstats.each{k,v->p['p_l_'+k]=v}
                call(sql,statsProc,p)
                sql.execute('INSERT INTO result_sync_match(source_key,match_id,run_id,date_precision,source_urls,raw_record) VALUES (?,?,?,?,?::jsonb,?::jsonb) ON CONFLICT(source_key) DO NOTHING',[m.key,m.existingMatchId,runId,m.datePrecision,JsonOutput.toJson(m.sourceUrls),JsonOutput.toJson(m.raw)])
            }
            sql.execute('UPDATE result_sync_run SET completed_at=now(),inserted=? WHERE run_id=?',[ordered.size(),runId])
        }
        log('RESULTS_IMPORT_COMPLETE',[runId:runId,inserted:plan.inserts.size(),through:data.through])
        [runId:runId,inserted:plan.inserts.size()]
    }
    void run() {
        Sql sql=database()
        try {
            if(!sql.firstRow('SELECT pg_try_advisory_lock(7262601) AS acquired').acquired) throw new IllegalStateException('Another results sync is running')
            String through=env.SYNC_THROUGH ?: LocalDate.now(ZoneId.of('America/Cancun')).toString()
            def table=sql.firstRow("SELECT to_regclass('tcb.result_sync_run') AS name").name
            String checkpoint=table?sql.firstRow('SELECT max(through_date)::text AS d FROM result_sync_run WHERE derived_completed').d:null
            String last=checkpoint ?: sql.firstRow('SELECT max(e.date)::text AS d FROM tournament_event e JOIN match m USING(tournament_event_id)').d
            String from=env.SYNC_FROM ?: LocalDate.parse(last).minusDays(21).toString()
            Map data
            if(env.SYNC_SEED_ID) {
                def parts=sql.rows('SELECT part,payload FROM result_sync_seed WHERE seed_id=? ORDER BY part',[env.SYNC_SEED_ID])
                if(!parts || parts.withIndex().any{p,i->p.part!=i})throw new IllegalStateException('Missing seed snapshot chunks')
                byte[] compressed=parts*.payload.join('').decodeBase64()
                String payload=new java.util.zip.GZIPInputStream(new ByteArrayInputStream(compressed)).getText('UTF-8')
                data=new JsonSlurper().parseText(payload) as Map
                from=data.from;through=data.through
                log('SOURCE_SNAPSHOT_LOADED',[from:from,through:through,matches:data.matches.size(),profiles:data.profiles])
            } else {
                sql.rows('SELECT DISTINCT ON(tm.ext_tournament_id) tm.ext_tournament_id,t.name,t.level::text,t.surface::text,e.draw_size FROM tournament_mapping tm JOIN tournament t USING(tournament_id) LEFT JOIN tournament_event e USING(tournament_id) ORDER BY tm.ext_tournament_id,e.season DESC NULLS LAST').each{r->tournamentDefaults[r.ext_tournament_id]=r as Map}
                data=collect(from,through)
            }
            Map planned=plan(sql,data)
            if(env.SYNC_APPLY!='true') { log('SYNC_DRY_RUN_COMPLETE',[inserts:planned.inserts.size(),rejects:planned.rejects.size(),conflicts:planned.sourceConflicts.size(),missing:planned.espnMissing.size()]);return }
            Map imported=apply(sql,data,planned)
            if(imported.inserted || sql.firstRow('SELECT count(*)::integer AS n FROM result_sync_run WHERE NOT derived_completed').n) {
                System.setProperty('tcb.db.url','jdbc:postgresql://'+env.PGHOST+':'+(env.PGPORT?:'5432')+'/'+env.PGDATABASE+'?currentSchema=tcb,public')
                System.setProperty('tcb.db.username',env.PGUSER)
                System.setProperty('tcb.db.password',env.PGPASSWORD)
                System.setProperty('tcb.data.full-load','false')
                System.setProperty('tcb.data.record-pause','0')
                log('DERIVED_DATA_START',[from:from])
                def ds=org.strangeforest.tcb.dataload.SqlPool.connectionPoolDataSource(3)
                try { new org.strangeforest.tcb.stats.model.elo.EloRatingsManager(ds).compute(true,false,LocalDate.parse(from),2) } finally { ds.close() }
                def loader=new org.strangeforest.tcb.dataload.ATPTennisLoader()
                def pool=new org.strangeforest.tcb.dataload.SqlPool(1)
                try {
                    pool.withSql { s -> loader.refreshMaterializedViews(s) }
                    pool.withSql { s -> new org.strangeforest.tcb.dataload.RecordsLoader().loadRecords(loader,s) }
                } finally { pool.each{it.close()} }
                sql.execute('UPDATE result_sync_run SET derived_completed=true WHERE NOT derived_completed')
            }
            def verification=sql.firstRow('SELECT count(*)::integer AS matches,max(m.date)::text AS latest_match,count(*) FILTER(WHERE m.winner_elo_rating IS NULL OR m.loser_elo_rating IS NULL)::integer AS missing_elo FROM match m JOIN tournament_event e USING(tournament_event_id) WHERE e.season=?',[through.take(4).toInteger()])
            log('WEEKLY_SYNC_COMPLETE',[runId:imported.runId,inserted:imported.inserted,verification:verification])
        } finally { sql.close() }
    }
}

if(args && args[0]=='--test') {
    assert RecentResultsSync.norm('Félix Auger-Aliassime')=='felixaugeraliassime'
    def s=RecentResultsSync.score('7-6(5) 6-2')
    assert s.ws==2 && s.ls==0 && s.wg==13 && s.sets[0].wt==7 && s.sets[0].lt==5
    assert RecentResultsSync.score('5-1 RET').ws==0
    assert RecentResultsSync.score('W/O').sets==[]
    boolean rejected=false; try{RecentResultsSync.score('2-6 4-6')}catch(IllegalArgumentException ignored){rejected=true};assert rejected
    assert RecentResultsSync.parseTA('var matchmx = ['+JsonOutput.toJson((0..<48).collect{''})+'];').size()==1
    println 'SOURCE_PARSER_TESTS_PASSED'
} else if(args && args[0]=='--collect') {
    new RecentResultsSync().collect(args[1],args[2])
} else if(args && args[0] in ['--plan','--apply']) {
    def sync=new RecentResultsSync();Map data=new JsonSlurper().parse(new File(args[1])) as Map
    def sql=sync.database()
    try {
        if(!sql.firstRow('SELECT pg_try_advisory_lock(7262601) AS acquired').acquired) throw new IllegalStateException('Another results sync is running')
        Map plan=sync.plan(sql,data)
        if(args[0]=='--apply') sync.apply(sql,data,plan)
    }finally{sql.close()}
} else if(args && args[0]=='--sync') {
    new RecentResultsSync().run()
}
