FROM tmaxopensql/postgres:14.6

USER root

RUN curl -L https://github.com/pgvector/pgvector/archive/refs/tags/v0.8.0.tar.gz \
        -o /tmp/pgvector.tar.gz \
    && tar -xzf /tmp/pgvector.tar.gz -C /tmp \
    && cd /tmp/pgvector-0.8.0 \
    && make PG_CONFIG=/usr/pgsql-14/bin/pg_config \
    && make install PG_CONFIG=/usr/pgsql-14/bin/pg_config \
    && rm -rf /tmp/pgvector.tar.gz /tmp/pgvector-0.8.0

COPY init-and-start.sh /usr/local/bin/init-and-start.sh
RUN chmod +x /usr/local/bin/init-and-start.sh

CMD ["/usr/local/bin/init-and-start.sh"]
