# Seat Reservation

## Local PostgreSQL development

Prerequisites: Docker Compose, Java 21 or later, and Maven.

1. Create your local Compose environment file and start PostgreSQL:

   ```powershell
   Copy-Item .env.example .env
   docker compose up -d postgres
   ```

   `.env.example` contains local-only development values. Change them if needed; never use
   those values in production. Compose persists database files in the `postgres_data` volume.

2. Set the same database values for the Spring Boot process and run the application:

   ```powershell
   $env:POSTGRES_DB = "seat_reservation"
   $env:POSTGRES_USER = "seat_reservation"
   $env:POSTGRES_PASSWORD = "seat_reservation_dev"
   $env:POSTGRES_PORT = "5433"
   mvn spring-boot:run "-Dspring-boot.run.profiles=local"
   ```

   The `local` profile connects to PostgreSQL on `localhost:5433` by default. Set
   `POSTGRES_PORT=5432` in `.env` and in the application environment if you prefer the standard port.
   `application.yml` uses
   `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` for other environments;
   provide those through the deployment environment and do not commit production credentials.

3. Stop PostgreSQL when finished:

   ```powershell
   docker compose down
   ```

   To also remove the local database volume, run `docker compose down -v`.

Flyway is enabled and uses its default migration location, `classpath:db/migration`. No
business tables or migrations are included yet. Hibernate schema generation is set to
`validate`, so it will not create application tables.
