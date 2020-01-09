CREATE KEYSPACE IF NOT EXISTS teams WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1 };

CREATE TABLE IF NOT EXISTS teams.state (
    id TEXT,
    state TEXT,
    PRIMARY KEY (id)
);