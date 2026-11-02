import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.sql.Sql
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.ZoneId
import java.text.Normalizer
import java.security.MessageDigest

/** Official weekly snapshots, independently checked before touching player_ranking. */
class WeeklyRankingsSync {
    static final int TOP = 500 // Matches the existing LoadNewRankings contract.
    static final Map URLS = [points:'https://www.tennislive.net/atp/ranking/',
        identities:'https://www.tennisabstract.com/reports/atpRankings.html',
        check:'https://www.tennisexplorer.com/ranking/atp-men/']
    static final Map IDENTITIES = new JsonSlurper().parse(new File(
        new File(WeeklyRankingsSync.protectionDomain.codeSource.location.toURI()).parentFile,
        'ranking-identities.json')) as Map
    Map env
    WeeklyRankingsSync(Map environment=System.getenv()) { env=environment }
    static String norm(Object value) {
        String n=Normalizer.normalize(value?.toString()?:'',Normalizer.Form.NFD)
            .replaceAll('\\p{M}','').toLowerCase(Locale.ROOT).replaceAll('[^a-z0-9]','')
        [taylorharryfritz:'taylorfritz',jaumeantonimunarclar:'jaumemunar',chaklamcolemanwong:'colemanwong',
         carlosalcarazgarfia:'carlosalcaraz',danielmeridaaguilar:'danielmerida',zhangzhizhen:'zhizhenzhang',
         shangjuncheng:'junchengshang',wuyibing:'yibingwu',buyunchaokete:'yunchaoketebu',sunfajing:'fajingsun',
         zhouyi:'yizhou',cuijie:'jiecui',pedromartinezportero:'pedromartinez',diegodedurapalomero:'diegodedura',
         jeffwolf:'jjwolf',jeffreyjohnwolf:'jjwolf',thiagomouramonteiro:'thiagomonteiro',manasmanojdhamne:'manasdhamne',
         khumounsultanov:'khumoyunsultanov',santiagofarodrigueztaverna:'santiagorodrigueztaverna',
         pauloandresaraivadossantos:'paulosaraivadossantos',olukayodealafiadaminaayeni:'alafiaayeni',
         murkelalejandrodellienvelasco:'murkeldellien',nikitamashtakov:'mykytamashtakov',
         gabiadrianboitan:'adrianboitan',juanalejandrohernandezserrano:'juanalejandrohernandez',
         lloydgeorgemuirheadharris:'lloydharris',marcelotomasbarriosvera:'tomasbarriosvera',christiangarin:'cristiangarin',
         juanpablovarillaspatinosamudio:'juanpablovarillas',dominicstephanstricker:'dominicstricker',
         felipemeligenirodriguesalves:'felipemeligenialves',moezchargui:'moezechargui',
         danielelahigalanriveros:'danielelahigalan',holgervitusnodskovrune:'holgerrune',
         martindammjr:'martindamm',migueltobonjr:'migueltobon'][n] ?: n
    }
    static void log(String type,Object value) { println type+' '+JsonOutput.toJson(value) }
    String get(String url) {
        def request=Jsoup.connect(url).userAgent('Mozilla/5.0').timeout(30000).maxBodySize(5*1024*1024)
        String proxy=env.HTTPS_PROXY ?: env.HTTP_PROXY
        if(proxy) { def uri=new URI(proxy);request.proxy(uri.host,uri.port) }
        request.get().outerHtml()
    }
    static Map parsePoints(String html) {
        def doc=Jsoup.parse(html)
        String date=doc.select('[data-ranking-date] option[selected]').attr('value')
        List rows=doc.select('[data-ranking-panel=world] article').collect { article ->
            List strong=article.select('strong')
            String rank=strong?strong[0].ownText().replace('#','').trim():''
            String points=strong.size()>1?strong[1].ownText().replace(',','').trim():''
            [rank:rank.isInteger()?rank.toInteger():null,points:points.isInteger()?points.toInteger():null,
             name:article.select('a[href^=/atp/] span.block.truncate').text(),
             profile:'https://www.tennislive.net'+article.select('a[href^=/atp/]').attr('href'),
             country:article.select('span.hidden.items-center > span').text()]
        }
        [date:date,rows:rows]
    }
    static Map parseIdentities(String html) {
        def doc=Jsoup.parse(html)
        def date=doc.text() =~ /Last update:\s*(\d{4}-\d{2}-\d{2})/
        List rows=doc.select('#reportable tr').findAll{it.select('td').size()==4}.collect { tr ->
            def td=tr.select('td');String rank=td[0].text()
            [rank:rank.isInteger()?rank.toInteger():null,name:td[1].text(),country:td[2].text(),dob:td[3].text()]
        }
        [date:date.find()?date.group(1):null,rows:rows]
    }
    static Map profile(String html) {
        List people=[]
        Jsoup.parse(html).select('script[type=application/ld+json]').each { tag ->
            Map json=new JsonSlurper().parseText(tag.data()) as Map
            List nodes=json['@graph'] instanceof List?json['@graph']:[json]
            nodes.each { n ->
                if(n['@type']=='Person')people<<n
                if(n.mainEntity instanceof Map && n.mainEntity['@type']=='Person')people<<n.mainEntity
            }
        }
        if(people.size()!=1)throw new IllegalStateException('Expected one player identity in profile')
        [name:people[0].name,dob:people[0].birthDate]
    }
    static Map parseCheck(String html) {
        def doc=Jsoup.parse(html)
        String date=doc.select('select option[selected]').collect{it.attr('value')}.find{it==~ /\d{4}-\d{2}-\d{2}/}
        List rows=doc.select('tbody.flags tr').collect { tr ->
            String rank=tr.select('td.rank').text().replace('.','')
            String points=tr.select('td.long-point').text().replace(',','')
            // Tennis Explorer prints surname before given names. Compare sorted tokens.
            [rank:rank.isInteger()?rank.toInteger():null,points:points.isInteger()?points.toInteger():null,
             name:tr.select('td.t-name').text()]
        }
        [date:date,rows:rows]
    }
    static String tokens(String name) {
        Normalizer.normalize(name,Normalizer.Form.NFD).replaceAll('\\p{M}','').toLowerCase(Locale.ROOT)
            .replaceAll('[^a-z0-9]+',' ').trim().split(' ').sort().join(' ')
    }
    static String digest(Map data) {
        MessageDigest.getInstance('SHA-256').digest(JsonOutput.toJson([date:data.date,rows:data.rows]).getBytes('UTF-8')).encodeHex().toString()
    }
    static void validate(Map data,LocalDate today=LocalDate.now(ZoneId.of('America/Cancun'))) {
        LocalDate date=LocalDate.parse(data.date)
        if(date>today || date<today.minusDays(14) || date.dayOfWeek.value!=1) throw new IllegalStateException('Stale or invalid official ranking date: '+date)
        List rows=data.rows
        if(rows.size()!=TOP || rows[0].rank!=1 || rows[-1].rank!=TOP || rows*.rank!=(1..TOP).toList())
            throw new IllegalStateException('Incomplete top '+TOP+' ranking snapshot')
        if(rows.collect{norm(it.name)}.unique().size()!=TOP) throw new IllegalStateException('Duplicate player in ranking snapshot')
        rows.eachWithIndex { r,i ->
            if(!r.name || !(r.country==~ /[A-Z]{3}/) || !(r.dob==~ /\d{4}-\d{2}-\d{2}/) || !r.points || r.points<1)
                throw new IllegalStateException('Invalid ranking row '+r.rank)
            LocalDate dob=r.dob?LocalDate.parse(r.dob):null
            if(dob && (dob>date.minusYears(13) || dob<date.minusYears(65))) throw new IllegalStateException('Invalid birthdate at rank '+r.rank)
            if(i && r.points>rows[i-1].points) throw new IllegalStateException('Ranking points out of order')
        }
    }
    static Map reconcile(Map points,Map identities,Map check,LocalDate today=LocalDate.now(ZoneId.of('America/Cancun'))) {
        if(!points.date || points.date!=identities.date || points.date!=check.date) throw new IllegalStateException('Ranking sources have different dates')
        if(check.rows.size()!=50 || check.rows*.rank!=(1..50).toList()) throw new IllegalStateException('Incomplete independent points check')
        Map names=identities.rows.groupBy{norm(it.name)}
        List rows=points.rows.take(TOP).collect { r ->
            List found=names[norm(r.name)]?:[]
            if(found.size()!=1 || found[0].rank!=r.rank) throw new IllegalStateException('Ranking source disagreement for '+r.name)
            [rank:r.rank,points:r.points,name:found[0].name,dob:found[0].dob,country:found[0].country]
        }
        check.rows.each { r ->
            Map expected=rows.find{it.rank==r.rank}
            if(!expected || r.points!=expected.points || tokens(r.name)!=tokens(expected.name))
                throw new IllegalStateException('Independent points disagreement at rank '+r.rank)
        }
        Map data=[date:points.date,rows:rows,sources:URLS]
        validate(data,today);data.sha256=digest(data);data
    }
    Map collect() {
        Map points=parsePoints(get(URLS.points)),identities=parseIdentities(get(URLS.identities))
        identities.rows.findAll{it.rank<=TOP && !it.dob}.each { r ->
            def row=points.rows.find{it.rank==r.rank && norm(it.name)==norm(r.name)}
            if(!row || !row.profile.startsWith('https://www.tennislive.net/atp/'))throw new IllegalStateException('Missing identity profile for '+r.name)
            Map person=profile(get(row.profile))
            if(norm(person.name)!=norm(r.name))throw new IllegalStateException('Profile identity disagreement for '+r.name)
            r.dob=person.dob
            Thread.sleep(1200)
        }
        Map data=reconcile(points,identities,parseCheck(get(URLS.check)))
        // These explicit cases require a second, distinct player profile. The
        // registry never edits the six legacy profiles or their historical data.
        (IDENTITIES.bindings+IDENTITIES.newPlayers).each { expected ->
            Map row=data.rows.find{norm(it.name)==norm(expected.name)}
            if(!row)return
            String expectedDob=expected.sourceDob ?: expected.dob
            String expectedCountry=expected.sourceCountry ?: expected.country
            if(row.dob!=expectedDob || row.country!=expectedCountry)throw new IllegalStateException('Reviewed source identity changed: '+row.name)
            Map point=points.rows.find{norm(it.name)==norm(row.name)}
            if(point.profile!=expected.profile)throw new IllegalStateException('Reviewed profile URL changed: '+row.name)
            Map person=profile(get(expected.profile))
            if(norm(person.name)!=norm(row.name) || person.dob!=(expected.profileDob ?: expected.dob))
                throw new IllegalStateException('Reviewed profile identity changed: '+row.name)
            if(expected.pointsCountry && point.country!=expected.pointsCountry)throw new IllegalStateException('Player country sources disagree: '+row.name)
            row.verifiedProfile=[url:expected.profile,dob:person.dob]
            Thread.sleep(1200)
        }
        data.sha256=digest(data)
        log('RANKINGS_SOURCE_VERIFIED',[date:data.date,rows:data.rows.size(),sha256:data.sha256,top10:data.rows.take(10)])
        data
    }
    Sql database(boolean readOnly) {
        Sql sql=Sql.newInstance('jdbc:postgresql://'+env.PGHOST+':'+(env.PGPORT?:'5432')+'/'+env.PGDATABASE+'?currentSchema=tcb,public',env.PGUSER,env.PGPASSWORD,'org.postgresql.Driver')
        sql.connection.readOnly=readOnly;sql
    }
    Map plan(Sql sql,Map data) {
        validate(data)
        if(data.sha256!=digest(data)) throw new IllegalStateException('Ranking snapshot checksum mismatch')
        String latest=sql.firstRow('SELECT max(rank_date)::text AS date FROM player_ranking').date
        if(latest && data.date<latest) throw new IllegalStateException('Ranking snapshot would go backwards')
        Map names=sql.rows('SELECT player_id,full_name(first_name,last_name) AS name,dob::text AS dob,country_id AS country FROM player').groupBy{norm(it.name)}
        Set previouslyRanked=latest?sql.rows('SELECT player_id FROM player_ranking WHERE rank_date=?::date',[latest])*.player_id as Set:[] as Set
        List rejects=[],resolved=[],newPlayers=[],notes=[]
        data.rows.each { r ->
            List candidates=names[norm(r.name)]?:[]
            List exact=candidates.findAll{it.dob==r.dob && it.country==r.country}
            if(!exact)exact=candidates.findAll{!it.dob && it.country==r.country}
            if(exact.size()>1) {
                List current=exact.findAll{it.player_id in previouslyRanked}
                if(current.size()==1) {
                    exact=current
                    notes<<[name:r.name,reason:'Same-identity duplicate; retaining the existing ranked profile',playerId:current[0].player_id]
                }
            }
            if(exact.size()==1) {resolved<<r+[playerId:exact[0].player_id];return}
            Map binding=IDENTITIES.bindings.find{norm(it.name)==norm(r.name)}
            if(binding && r.dob==binding.sourceDob && r.country==binding.sourceCountry &&
                r.verifiedProfile==[url:binding.profile,dob:binding.profileDob]) {
                List bound=candidates.findAll{it.player_id==binding.playerId && it.dob==binding.storedDob && it.country==binding.storedCountry}
                if(bound.size()==1) {
                    resolved<<r+[playerId:binding.playerId]
                    notes<<[name:r.name,reason:'Reviewed legacy profile difference; profile is unchanged',playerId:binding.playerId,storedDob:binding.storedDob,storedCountry:binding.storedCountry]
                    return
                }
            }
            Map fresh=IDENTITIES.newPlayers.find{norm(it.name)==norm(r.name)}
            if(fresh && !exact && r.dob==fresh.dob && r.country==fresh.country &&
                r.verifiedProfile==[url:fresh.profile,dob:fresh.dob] &&
                candidates.any{it.player_id==fresh.olderPlayerId && it.dob==fresh.olderDob && it.country==fresh.olderCountry}) {
                newPlayers<<r
                resolved<<r+[newPlayer:true]
                return
            }
            rejects<<[rank:r.rank,name:r.name,reason:'Player name, birthdate and country must identify exactly one existing player']
        }
        List knownIds=resolved.findAll{!it.newPlayer}*.playerId
        if(knownIds.unique().size()!=resolved.count{!it.newPlayer}) rejects<<[reason:'Multiple rankings resolve to one player']
        Map existing=sql.rows('SELECT player_id,rank,rank_points FROM player_ranking WHERE rank_date=?::date',[data.date]).collectEntries{[(it.player_id):it]}
        existing.values().findAll{it.rank<=TOP && !(it.player_id in resolved*.playerId)}.each { old ->
            rejects<<[rank:old.rank,reason:'Stored top ranking belongs to an unexpected player; explicit repair required']
        }
        resolved.each { r ->
            def old=existing[r.playerId]
            if(old && (old.rank!=r.rank || old.rank_points!=r.points)) rejects<<[rank:r.rank,name:r.name,reason:'Existing ranking disagrees; explicit repair required']
        }
        Map plan=[date:data.date,previousDate:latest,rows:resolved,newPlayers:newPlayers.collect{[name:it.name,dob:it.dob,country:it.country]},identityNotes:notes,inserts:resolved.count{it.newPlayer || !existing[it.playerId]},rejects:rejects,sha256:data.sha256]
        log('RANKINGS_IMPORT_PLAN',plan.findAll{k,v->k!='rows'});plan
    }
    void apply(Sql sql,Map data) {
        Map completed
        sql.withTransaction {
            if(!sql.firstRow('SELECT pg_try_advisory_xact_lock(7262601) AS acquired').acquired) throw new IllegalStateException('Another tennis sync is running')
            Map planned=plan(sql,data) // Recheck identities and stored rows under the ingestion lock.
            if(planned.rejects) throw new IllegalStateException('Ranking import validation failed')
            planned.rows.each { r ->
                if(r.newPlayer) {
                    String[] name=r.name.split(' ',2)
                    r.playerId=sql.firstRow('INSERT INTO player(first_name,last_name,dob,country_id,active) VALUES (?,?,?::date,?,true) RETURNING player_id',[name[0],name[1],r.dob,r.country]).player_id
                }
                sql.executeInsert('INSERT INTO player_ranking(rank_date,player_id,rank,rank_points) VALUES (?::date,?,?,?) ON CONFLICT (rank_date,player_id) DO NOTHING',[data.date,r.playerId,r.rank,r.points])
            }
            ['player_current_rank','player_best_rank','player_best_rank_points','player_year_end_rank'].each { view ->
                sql.execute('REFRESH MATERIALIZED VIEW '+view)
            }
            completed=[date:data.date,inserted:planned.inserts,newPlayers:planned.newPlayers.size(),sha256:data.sha256]
        }
        log('RANKINGS_IMPORT_COMPLETE',completed)
    }
    void run() {
        Map data=collect()
        boolean write=env.RANKINGS_APPLY=='true'
        Sql sql=database(!write)
        try {
            if(write) apply(sql,data)
            else if(plan(sql,data).rejects)throw new IllegalStateException('Ranking preview validation failed')
        } finally {sql.close()}
    }
}

if(args && args[0]=='--collect') {
    Map data=new WeeklyRankingsSync().collect()
    if(args.size()>1)new File(args[1]).setText(JsonOutput.prettyPrint(JsonOutput.toJson(data)),'UTF-8')
} else if(args && args[0] in ['--plan','--apply']) {
    def sync=new WeeklyRankingsSync();Map data=new JsonSlurper().parse(new File(args[1])) as Map
    Sql sql=sync.database(args[0]!='--apply')
    try {if(args[0]=='--apply')sync.apply(sql,data);else sync.plan(sql,data)}finally{sql.close()}
} else if(args && args[0]=='--preview') new WeeklyRankingsSync(System.getenv()+[RANKINGS_APPLY:'false']).run()
else if(args && args[0]=='--sync') new WeeklyRankingsSync().run()
