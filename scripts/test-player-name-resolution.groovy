import groovy.sql.Sql

// Opt-in integration test. Use a disposable local PostgreSQL database only.
String url=System.getenv('IDENTITY_TEST_JDBC_URL')
assert url && url.matches('jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]+') : 'A local disposable IDENTITY_TEST_JDBC_URL is required'
def sql=Sql.newInstance(url,System.getenv('IDENTITY_TEST_USER')?:'postgres',System.getenv('IDENTITY_TEST_PASSWORD'),'org.postgresql.Driver')
try {
    sql.connection.autoCommit=false
    sql.execute('CREATE SCHEMA identity_resolution_test')
    sql.execute('SET LOCAL search_path=identity_resolution_test')
    sql.execute('CREATE TABLE player(player_id int, first_name text, last_name text, dob date)')
    sql.execute('CREATE TABLE player_alias(alias text, name text)')
    sql.execute('''CREATE FUNCTION full_name(text,text) RETURNS text LANGUAGE sql AS $$SELECT $1 || ' ' || $2$$''')
    String source=new File('data-load/src/main/db/load-functions.sql').text
    sql.execute(source.substring(source.indexOf('CREATE OR REPLACE FUNCTION find_player('),source.indexOf('-- load_player')))
    sql.execute("INSERT INTO player VALUES (2093,'Martin','Damm','1972-08-01'),(66208,'Martin','Damm','2003-09-30'),(3,'Unique','Player',NULL)")
    sql.execute("INSERT INTO player_alias VALUES ('M. Damm','Martin Damm'),('U. Player','Unique Player')")
    assert sql.firstRow("SELECT find_player('Martin Damm','1995-01-01') AS id").id==2093
    assert sql.firstRow("SELECT find_player('unique player',NULL) AS id").id==3
    assert sql.firstRow("SELECT find_player('U. Player',NULL) AS id").id==3
    ['Martin Damm','martin damm','M. Damm'].each { name ->
        sql.execute("""DO \$\$ BEGIN
            PERFORM find_player('${name}', DATE '2026-01-01');
            RAISE EXCEPTION 'Ambiguous name was accepted';
        EXCEPTION WHEN raise_exception THEN
            ASSERT SQLERRM LIKE 'Ambiguous player %', SQLERRM;
        END \$\$""".toString())
    }
    println 'PLAYER_NAME_RESOLUTION_TESTS_PASSED'
} finally { sql.connection.rollback();sql.close() }
