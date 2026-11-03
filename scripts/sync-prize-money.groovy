import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.sql.Sql
import org.jsoup.Jsoup
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.Duration

/** Refresh only career prize money, from identity-checked Wikipedia infoboxes. */
class PrizeMoneySync {
    Map env
    PrizeMoneySync(Map environment=System.getenv()) { env=environment }
    static void log(String type,Object value) { println type+' '+JsonOutput.toJson(value) }
    static String norm(String value) {
        Normalizer.normalize(value?:'',Normalizer.Form.NFD).replaceAll('\\p{M}','')
            .toLowerCase(Locale.ROOT).replaceAll('[^a-z0-9]','')
    }
    static boolean article(String url) {
        if(!url)return false
        URI uri
        try { uri=new URI(url) } catch(URISyntaxException ignored) { return false }
        uri.scheme=='https' && uri.host=='en.wikipedia.org' && uri.port==-1 && !uri.userInfo &&
            uri.path?.startsWith('/wiki/') && !uri.query && !uri.fragment &&
            !uri.path.substring(6).contains(':')
    }
    static Map parse(String html,Map player) {
        def doc=Jsoup.parse(html)
        def boxes=doc.select('table.infobox').findAll { box ->
            box.select('tr').any { it.select('th').text().equalsIgnoreCase('Prize money') }
        }
        if(boxes.size()!=1)throw new IllegalStateException('Expected one prize-money infobox')
        def box=boxes[0]
        List names=box.select('.fn, caption').collect{it.text()}
        names<<doc.select('#firstHeading').text().replaceFirst(/\s+\(tennis\)$/, '')
        box.select('tr').findAll{it.select('th').text().equalsIgnoreCase('Full name')}.each {
            names<<it.select('td').text()
        }
        if(!norm(player.name) || !names.any{norm(it)==norm(player.name)})
            throw new IllegalStateException('Wikipedia name does not match stored player')
        List dates=box.select('.bday').collect{it.text()}.unique()
        if(!player.dob || dates!=[player.dob.toString()])
            throw new IllegalStateException('Wikipedia birthdate does not match stored player')
        def rows=box.select('tr').findAll{it.select('th').text().equalsIgnoreCase('Prize money')}
        if(rows.size()!=1)throw new IllegalStateException('Ambiguous prize-money field')
        def cell=rows[0].select('td').first()?.clone()
        if(!cell)throw new IllegalStateException('Missing prize-money value')
        // References and the all-time earnings ranking are not part of the amount.
        cell.select('sup, .reference, .mw-ref, ul, ol').remove()
        String value=cell.text().replace('\u00a0',' ').trim()
        def match=value =~ /^(?:US\s*\$|USD\s*\$?|\$)\s*((?:[1-9]\d{0,2}(?:,\d{3})+)|(?:[1-9]\d*))(?:\s*\([^\d$]*\))?$/
        if(!match.matches())throw new IllegalStateException('Expected an unambiguous USD career total: '+value)
        long amount=match.group(1).replace(',','').toLong()
        if(amount>1000000000L)throw new IllegalStateException('Implausible career prize money')
        [prizeMoney:'$'+String.format(Locale.US,'%,d',amount),amount:amount,
         sourceName:names.find{norm(it)==norm(player.name)},sourceDob:dates[0]]
    }
    String get(String url) {
        if(!article(url))throw new IllegalStateException('Expected an English Wikipedia article URL')
        def request=Jsoup.connect(url).userAgent('TennisCrystalBall/1.0 (career prize money refresh)')
            .timeout(30000).maxBodySize(5*1024*1024).followRedirects(true)
        String proxy=env.HTTPS_PROXY ?: env.HTTP_PROXY
        if(proxy) { def uri=new URI(proxy);request.proxy(uri.host,uri.port) }
        def response=request.execute()
        if(!article(response.url().toString()))throw new IllegalStateException('Unexpected Wikipedia redirect')
        response.body()
    }
    Sql database(boolean readOnly) {
        Sql sql=Sql.newInstance('jdbc:postgresql://'+env.PGHOST+':'+(env.PGPORT?:'5432')+'/'+env.PGDATABASE+
            '?currentSchema=tcb,public',env.PGUSER,env.PGPASSWORD,'org.postgresql.Driver')
        sql.connection.readOnly=readOnly
        sql
    }
    List candidates(Sql sql,boolean currentOnly=false) {
        Set ids=env.PRIZE_MONEY_PLAYER_IDS?env.PRIZE_MONEY_PLAYER_IDS.split(',').collect{it.trim().toInteger()}.toSet():null
        String scope=currentOnly && !ids?' AND EXISTS (SELECT 1 FROM player_current_rank r WHERE r.player_id=p.player_id AND r.current_rank<=500)':''
        List rows=sql.rows('SELECT player_id,full_name(first_name,last_name) AS name,dob::text,wikipedia,prize_money FROM player p WHERE wikipedia IS NOT NULL AND dob IS NOT NULL'+scope+' ORDER BY player_id')
        if(ids) {
            rows=rows.findAll{it.player_id in ids}
            if(rows*.player_id.toSet()!=ids)throw new IllegalStateException('Requested player lacks Wikipedia URL or birthdate')
        }
        rows.collect{[playerId:it.player_id,name:it.name,dob:it.dob,wikipedia:it.wikipedia,previous:it.prize_money]}
    }
    static String digest(Map data) {
        MessageDigest.getInstance('SHA-256').digest(JsonOutput.toJson(
            [fetchedAt:data.fetchedAt,rows:data.rows,skipped:data.skipped]).getBytes('UTF-8')).encodeHex().toString()
    }
    Map collect(List players) {
        List rows=[],skipped=[]
        players.eachWithIndex { p,i ->
            if(i)Thread.sleep(1200)
            try { rows<<p+parse(get(p.wikipedia),p) }
            catch(org.jsoup.HttpStatusException e) {
                if(e.statusCode in [403,429])throw e // Stop when access is denied or rate-limited.
                skipped<<[playerId:p.playerId,name:p.name,reason:e.message]
            }
            catch(IllegalStateException e) { skipped<<[playerId:p.playerId,name:p.name,reason:e.message] }
        }
        Map data=[fetchedAt:Instant.now().toString(),rows:rows,skipped:skipped]
        data.sha256=digest(data)
        log('PRIZE_MONEY_SOURCE_VERIFIED',[considered:players.size(),verified:rows.size(),skipped:skipped.size(),sha256:data.sha256])
        data
    }
    static void validate(Map data) {
        if(!data.sha256 || data.sha256!=digest(data))throw new IllegalStateException('Snapshot checksum mismatch')
        Duration age=Duration.between(Instant.parse(data.fetchedAt),Instant.now())
        if(age.negative || age>Duration.ofDays(7))throw new IllegalStateException('Stale or future snapshot')
        if(data.rows.collect{it.playerId}.unique().size()!=data.rows.size())throw new IllegalStateException('Duplicate players')
        data.rows.each { r ->
            if(!article(r.wikipedia) || !r.playerId || !r.dob || norm(r.name)!=norm(r.sourceName) || r.dob!=r.sourceDob ||
                !(r.amount instanceof Number) || r.amount<1 || r.amount>1000000000L ||
                r.prizeMoney!='$'+String.format(Locale.US,'%,d',r.amount.toLong()))
                throw new IllegalStateException('Invalid source row for '+r.playerId)
        }
    }
    Map plan(Sql sql,Map data) {
        validate(data)
        Map current=sql.rows('SELECT player_id,full_name(first_name,last_name) AS name,dob::text,wikipedia,prize_money FROM player').collectEntries{[(it.player_id):it]}
        List changes=[],rejects=[]
        data.rows.each { r ->
            Map p=current[r.playerId]
            if(!p || p.name!=r.name || p.dob!=r.dob || p.wikipedia!=r.wikipedia)
                rejects<<[playerId:r.playerId,reason:'Stored identity or Wikipedia URL changed']
            else if(p.prize_money==r.prizeMoney) { /* Already applied. */ }
            else if(p.prize_money!=r.previous)
                rejects<<[playerId:r.playerId,reason:'Stored prize money changed since collection']
            else changes<<r
        }
        Map planned=[changes:changes,rejects:rejects,skipped:data.skipped]
        log('PRIZE_MONEY_PLAN',planned)
        planned
    }
    void apply(Sql sql,Map data) {
        List changes=[]
        sql.withTransaction {
            if(!sql.firstRow('SELECT pg_try_advisory_xact_lock(7262601) AS acquired').acquired)
                throw new IllegalStateException('Another tennis sync is running')
            Map planned=plan(sql,data)
            if(planned.rejects)throw new IllegalStateException('Prize-money plan has conflicting stored rows')
            changes=planned.changes
            changes.each { r ->
                int count=sql.executeUpdate('UPDATE player SET prize_money=? WHERE player_id=? AND full_name(first_name,last_name)=? AND dob::text=? AND wikipedia=? AND prize_money IS NOT DISTINCT FROM ?',
                    [r.prizeMoney,r.playerId,r.name,r.dob,r.wikipedia,r.previous])
                if(count!=1)throw new IllegalStateException('Concurrent player update: '+r.playerId)
            }
        }
        changes.each { log('PRIZE_MONEY_UPDATED',[sha256:data.sha256,change:it]) }
        log('PRIZE_MONEY_APPLIED',[sha256:data.sha256,updated:changes.size()])
    }
    void run() {
        if(env.PRIZE_MONEY_SYNC!='true')return
        Sql read=database(true)
        Map data
        try { data=collect(candidates(read,true));plan(read,data) } finally { read.close() }
        if(env.PRIZE_MONEY_APPLY=='true') {
            Sql write=database(false)
            try { apply(write,data) } finally { write.close() }
        }
    }
}

if(args && args[0] in ['--collect','--preview']) {
    def sync=new PrizeMoneySync();def sql=sync.database(true)
    try {
        Map data=sync.collect(sync.candidates(sql))
        if(args[0]=='--collect')new File(args[1]).text=JsonOutput.prettyPrint(JsonOutput.toJson(data))
        sync.plan(sql,data)
    } finally {sql.close()}
} else if(args && args[0] in ['--plan','--apply']) {
    def sync=new PrizeMoneySync();Map data=new JsonSlurper().parse(new File(args[1])) as Map
    def sql=sync.database(args[0]!='--apply')
    try {if(args[0]=='--apply')sync.apply(sql,data);else sync.plan(sql,data)} finally {sql.close()}
} else if(args && args[0]=='--sync')new PrizeMoneySync().run()
