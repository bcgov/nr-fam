FROM flyway/flyway:13-alpine

# Copy migrations
COPY ./sql /flyway/sql

# Non-root user
RUN adduser -D app
USER app

# The FAM schema. Flyway's history table is created here too.
ENV FLYWAY_SCHEMAS=app_fam

# Health check and startup
HEALTHCHECK CMD info
CMD ["info", "migrate", "info"]
