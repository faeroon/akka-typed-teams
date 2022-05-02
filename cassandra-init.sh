CQL="DROP keyspace teams;
CREATE KEYSPACE teams WITH replication = {'class': 'SimpleStrategy', 'replication_factor': '1'};
CREATE TABLE teams.teams (id text, state text, PRIMARY KEY (id));"

until echo $CQL | cqlsh; do
  echo "cqlsh: Cassandra is unavailable to initialize - will retry later"
  sleep 2
done &

exec /docker-entrypoint.sh "$@"