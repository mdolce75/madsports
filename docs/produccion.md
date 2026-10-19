# Producción

Guía para poner RinkMetrics en un servidor: base PostgreSQL gestionada, variables de entorno, health check, chequeo
diario de SIDGAD y respaldos. Localmente la app sigue andando con H2 en archivo, sin configurar nada.

## Base de datos: PostgreSQL

En producción la base es **PostgreSQL 16** (o más nuevo), gestionada por el proveedor (Render, Railway, Neon, Supabase,
AWS RDS, DigitalOcean...). La app solo necesita la URL JDBC y un usuario dueño del esquema:

| Variable | Ejemplo |
|---|---|
| `FPP_DB_URL` | `jdbc:postgresql://host:5432/rinkmetrics?sslmode=require` |
| `FPP_DB_USER` | `rinkmetrics` |
| `FPP_DB_PASSWORD` | la clave que da el proveedor |

Flyway crea y actualiza el esquema al arrancar. Las migraciones están separadas por motor
(`db/migration/{vendor}`):

- `db/migration/h2/`: las de la base local (H2).
- `db/migration/postgresql/`: las mismas, escritas para PostgreSQL (`timestamptz`, `alter column ... drop not null`,
  `position(...)`).

**Regla:** cada cambio de esquema se agrega como `V<n>__<nombre>.sql` en **las dos carpetas**, con el mismo número.
`MigracionesIT` verifica que las dos carpetas tengan las mismas versiones, y los tests unitarios (H2) y de integración
(PostgreSQL con Testcontainers) aplican cada una, así que una migración que falte o falle en un motor rompe el build. Una migración ya aplicada no se edita: Flyway compara el checksum y no arranca.

Hibernate valida el esquema contra las entidades (`ddl-auto: validate`): si no coinciden, la app no arranca.

### Pasar los datos de H2 a PostgreSQL

Si ya hay usuarios en una base H2 que se quiere conservar: arrancar la app una vez contra la base PostgreSQL vacía
(Flyway crea el esquema), detenerla y copiar los datos tabla por tabla (por ejemplo exportando cada tabla de H2 a CSV
con `CALL CSVWRITE(...)` e importándola con `\copy` de `psql`), en el orden de las claves foráneas: `suscripcion_type`,
`usuario`, `usuario_perfil`, `token_usuario`, `persistent_logins`, `equipo_dt`, `jugador_dt`, `jugador_equipo_dt`,
`partido_dt`, `accion_dt`, `partido_creado_dt`. Después, ajustar las secuencias (`select setval(...)`) al máximo id de
cada tabla.

## Variables de entorno

Además de las de la base y las del README (`SMTP_*`, `MAIL_FROM`, `APP_BASE_URL`, `GEMINI_API_KEY`...):

| Variable | Default | |
|---|---|---|
| `FORWARD_HEADERS_STRATEGY` | `none` | `native` detrás de un proxy o balanceador (casi todos los proveedores): toma la IP real y el https de `X-Forwarded-For` / `X-Forwarded-Proto`. Sin esto, el límite de intentos ve a todos los usuarios con la IP del proxy |
| `ALERTAS_EMAIL` | vacío | Dirección que recibe las alertas del chequeo diario de SIDGAD. Vacío: solo quedan en el log |
| `MONITOR_SIDGAD_CRON` | `0 0 8 * * *` | Cuándo corre el chequeo (hora argentina); `-` lo apaga |
| `LIMITE_LOGIN_USUARIO` | `5` | Ingresos fallidos de un usuario en 15 minutos |
| `LIMITE_LOGIN_IP` | `20` | Ingresos fallidos desde una IP en 15 minutos |
| `LIMITE_REGISTRO_IP` | `5` | Altas de cuenta desde una IP por hora |
| `LIMITE_MAILS_IP` | `10` | Pedidos de mails (reenviar confirmación, recuperar la clave) desde una IP por hora |
| `RECORDARME_CLAVE` | al azar | Fijarla si hay más de una instancia |

Los contadores del límite de intentos viven en memoria: con una sola instancia alcanza. Con varias, cada una cuenta lo
suyo (el límite real se multiplica por la cantidad de instancias).

## Health check

`GET /actuator/health` es público y devuelve el estado general y el de cada componente, sin detalles:

```json
{"status":"UP","components":{"db":{"status":"UP"},"diskSpace":{"status":"UP"},"sidgad":{"status":"UP"}, ...}}
```

- `db`: la base responde.
- `sidgad`: resultado del último chequeo diario (UNKNOWN hasta el primero, que corre a la hora del cron). Si falló,
  queda DOWN y el estado general pasa a DOWN (HTTP 503).
- `GET /actuator/health/liveness`: solo dice si la app está viva. **Es el que hay que configurar como sonda del
  proveedor** (health check path), para que una falla de SIDGAD no haga reiniciar la app.
- `GET /actuator/health/readiness`: lista para recibir tráfico.

Para monitoreo externo (UptimeRobot, Better Stack...), apuntar a `/actuator/health`: avisa si se cae la app, la base o
la lectura de SIDGAD.

## Chequeo diario de SIDGAD

Los informes dependen de leer el HTML de SIDGAD; si el sitio cambia de formato, los parsers devuelven listas vacías
sin dar error y los informes salen vacíos. `SidgadMonitor` recorre una vez por día el camino de un informe con datos
reales:

1. El portal trae la temporada de alguno de los años ofrecidos (`rinkmetrics.sidgad.years`).
2. El catálogo trae competiciones de la FPP para ese año.
3. Los calendarios de las primeras competiciones traen partidos.
4. El acta de un partido jugado trae los planteles.

Si un paso falla (o SIDGAD no responde), manda un mail a `ALERTAS_EMAIL` con el paso que falló, deja el componente
`sidgad` en DOWN y lo escribe en el log (`Chequeo de SIDGAD: FALLA`). Mientras siga fallando, avisa cada día; cuando
vuelve a andar, manda un mail de recuperación. Calendarios sin partidos jugados (temporada que recién empieza) no son
una falla.

Qué hacer ante una alerta: abrir el portal de SIDGAD, guardar el fragmento que cambió en `src/test/resources/fixtures`,
ver qué test de parser falla y ajustar `SidgadParser`.

## Respaldos

Con base gestionada, los respaldos los hace el proveedor. Al crear la base:

1. **Activar los respaldos automáticos diarios** y, si el plan lo ofrece, la **recuperación a un momento dado**
   (point-in-time recovery, PITR).
2. **Retención:** al menos 7 días (ideal 30).
3. Confirmar que los respaldos quedan en otra zona o región que la base, si el proveedor lo permite.
4. Anotar en qué pantalla del proveedor se restaura y quién tiene acceso.

Además, una vez por mes (y antes de cada migración grande), un respaldo lógico propio, fuera del proveedor:

```bash
pg_dump --format=custom --no-owner --dbname="postgresql://USUARIO:CLAVE@HOST:5432/rinkmetrics?sslmode=require" \
  --file=rinkmetrics-$(date +%F).dump
```

Guardarlo cifrado fuera del proveedor (contiene emails y claves hasheadas de los usuarios).

### Restaurar

- **Desde el proveedor:** restaurar el respaldo (o el momento PITR) en una **base nueva**, apuntar `FPP_DB_URL` a esa
  base y reiniciar la app. No restaurar encima de la base en uso.
- **Desde un `pg_dump`:**

  ```bash
  createdb rinkmetrics_restaurada
  pg_restore --no-owner --dbname="postgresql://USUARIO:CLAVE@HOST:5432/rinkmetrics_restaurada" rinkmetrics-AAAA-MM-DD.dump
  ```

  Al arrancar, Flyway aplica las migraciones que falten respecto del respaldo.

**Probar la restauración** cada 3 meses en una base aparte: restaurar, arrancar la app contra ella y entrar con un
usuario. Un respaldo que nunca se restauró no está probado.

## Integración continua

`.github/workflows/ci.yml` corre `./mvnw verify` (unitarios con H2, integración con PostgreSQL 16 y cobertura) en cada
PR y en cada push a `main`. Para que no se pueda mergear con tests rotos, en GitHub: **Settings → Branches → Branch
protection rule** para `main`, con "Require status checks to pass" y el check `Backend`.
