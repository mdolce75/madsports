# Producción

Guía para poner Mad Sports en un servidor: base PostgreSQL gestionada, variables de entorno, dominios, health check, chequeo
diario de SIDGAD y respaldos. Localmente la app sigue andando con H2 en archivo, sin configurar nada.

## Base de datos: PostgreSQL

En producción la base es **PostgreSQL 16** (o más nuevo), gestionada por el proveedor (Render, Railway, Neon, Supabase,
AWS RDS, DigitalOcean...). La app solo necesita la URL JDBC y un usuario dueño del esquema:

| Variable | Ejemplo |
|---|---|
| `MADSPORTS_DB_URL` | `jdbc:postgresql://host:5432/rinkmetrics?sslmode=require` |
| `MADSPORTS_DB_USER` | `rinkmetrics` |
| `MADSPORTS_DB_PASSWORD` | la clave que da el proveedor |

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
con `CALL CSVWRITE(...)` e importándola con `\copy` de `psql`), en el orden de las claves foráneas: `subscription_plan`,
`user_account`, `user_profile`, `user_token`, `persistent_logins`, `coach_team`, `coach_player`, `coach_player_team`,
`coach_match`, `coach_action`, `coach_started_match`. Después, ajustar las secuencias (`select setval(...)`) al máximo id de
cada tabla.

## Variables de entorno

Además de las de la base y las del README (`SMTP_*`, `MAIL_FROM`, `APP_BASE_URL`, `GEMINI_API_KEY`...):

| Variable | Default | |
|---|---|---|
| `FORWARD_HEADERS_STRATEGY` | `none` | `native` detrás de un proxy o balanceador (casi todos los proveedores): toma la IP real y el https de `X-Forwarded-For` / `X-Forwarded-Proto`. Sin esto, el límite de intentos ve a todos los usuarios con la IP del proxy |
| `ALERTS_EMAIL` | vacío | Dirección que recibe las alertas del chequeo diario de SIDGAD. Vacío: solo quedan en el log |
| `SIDGAD_MONITOR_CRON` | `0 0 8 * * *` | Cuándo corre el chequeo (hora argentina); `-` lo apaga |
| `LIMIT_LOGIN_USER` | `5` | Ingresos fallidos de un usuario en 15 minutos |
| `LIMIT_LOGIN_IP` | `20` | Ingresos fallidos desde una IP en 15 minutos |
| `LIMIT_SIGNUP_IP` | `5` | Altas de cuenta desde una IP por hora |
| `LIMIT_MAILS_IP` | `10` | Pedidos de mails (reenviar confirmación, recuperar la clave) desde una IP por hora |
| `REMEMBER_ME_KEY` | al azar | Fijarla si hay más de una instancia |

Los contadores del límite de intentos viven en memoria: con una sola instancia alcanza. Con varias, cada una cuenta lo
suyo (el límite real se multiplica por la cantidad de instancias).

## Dominios

Una sola app sirve los tres sitios, cada uno con su dominio:

| Dominio | Muestra |
|---|---|
| `madsports.com.ar` | La home de Mad Sports |
| `rinkreports.com.ar` | RinkReports: la raíz es su portada; ingreso, registro, informes de club y de jugador |
| `rinkmetrics.com.ar` | RinkMetrics: la raíz es su portada; ingreso, registro, la planilla del DT y la demo |

Variables de la app:

```
APP_BASE_URL=https://madsports.com.ar
APP_URL_MADSPORTS=https://madsports.com.ar
APP_URL_RINKREPORTS=https://rinkreports.com.ar
APP_URL_RINKMETRICS=https://rinkmetrics.com.ar
MAIL_FROM=MadSports <info@madsports.com.ar>
FORWARD_HEADERS_STRATEGY=native
```

Con eso (`DomainFilter`):

- La raíz de cada producto es su portada.
- Las páginas de un producto pedidas en otro dominio llevan al suyo.
- El enlace "Mad Sports" de los productos lleva a la home.
- Los mails enlazan al dominio del producto de la cuenta y salen con su nombre ("RinkReports <info@madsports.com.ar>").

Las sesiones son de cada dominio, como las cuentas: una de RinkReports y otra de RinkMetrics.

### DNS (en el proveedor de DNS de cada dominio)

| Dominio | Registro | Valor |
|---|---|---|
| `madsports.com.ar`, `rinkreports.com.ar`, `rinkmetrics.com.ar` | `A` en `@` | La IP del servidor |
| Los tres | `CNAME` en `www` | El mismo dominio sin `www` (`madsports.com.ar`...) |
| `madsports.com.ar` | Los que pide Resend al agregar el dominio (`MX` y `TXT` de `send`, `TXT` de `resend._domainkey`) | Los que muestra Resend |

Resend, en el plan gratuito, verifica un dominio: los mails de los dos productos salen de `madsports.com.ar`. Para
recibir lo que escriben a `info@madsports.com.ar` (la dirección de contacto de la home) hace falta un reenvío a un correo
propio: Email Routing de Cloudflare (gratis) o el del proveedor del dominio.

### HTTPS: Caddy delante de la app

Caddy saca y renueva solo los certificados de los seis nombres (`www` incluidos) cuando el DNS ya apunta al servidor:

```
madsports.com.ar, rinkreports.com.ar, rinkmetrics.com.ar {
    reverse_proxy app:8080
}
www.madsports.com.ar {
    redir https://madsports.com.ar{uri} permanent
}
www.rinkreports.com.ar {
    redir https://rinkreports.com.ar{uri} permanent
}
www.rinkmetrics.com.ar {
    redir https://rinkmetrics.com.ar{uri} permanent
}
```

## Actas de SIDGAD guardadas y precarga

Las actas de los partidos jugados se bajan de SIDGAD una sola vez y quedan en la base (tabla `sidgad_game_sheet`,
comprimidas: unos 20 KB cada una, ~25 MB por año); en su primera semana se vuelven a revisar una vez por día por si
SIDGAD las corrige. Todos los días a las 5:00 (`SIDGAD_WARMUP_CRON`) una precarga baja las actas nuevas y la ficha de
temporada de cada jugador. Medido con VELEZ (13 categorías, temporada completa): el informe del club pasó de ~70 s a
~10 s; la primera precarga (base vacía) tardó 3,5 min (1.172 actas, 748 fichas), las siguientes solo bajan lo nuevo
y las fichas.

## Informes guardados

Cada informe que pide un club o un jugador se guarda (PDF, Excel y la versión para pantalla) para verlo y bajarlo
desde su historial, tal como salió ese día: en `REPORTS_DIR` (por defecto `./data/informes`), una carpeta por usuario.
En el servidor, esa carpeta va en un **volumen** (si no, se pierde al recrear el contenedor) y entra en los respaldos.
Pesan entre 0,1 y 2 MB por informe; se conservan `REPORTS_RETENTION_MONTHS` meses (12) y una tarea diaria borra los
más viejos.

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

1. El portal trae la temporada de alguno de los años ofrecidos (`madsports.sidgad.years`).
2. El catálogo trae competiciones de la FPP para ese año.
3. Los calendarios de las primeras competiciones traen partidos.
4. El acta de un partido jugado trae los planteles.

Si un paso falla (o SIDGAD no responde), manda un mail a `ALERTS_EMAIL` con el paso que falló, deja el componente
`sidgad` en DOWN y lo escribe en el log (`Chequeo de SIDGAD: FALLA`). Mientras siga fallando, avisa cada día; cuando
vuelve a andar, manda un mail de recuperación. Calendarios sin partidos jugados (temporada que recién empieza) no son
una falla.

Qué hacer ante una alerta: abrir el portal de SIDGAD, guardar el fragmento que cambió en `app/src/test/resources/fixtures`,
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

- **Desde el proveedor:** restaurar el respaldo (o el momento PITR) en una **base nueva**, apuntar `MADSPORTS_DB_URL` a esa
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
protection rule** para `main`, con "Require status checks to pass" y los checks `Backend` y `Pantallas`.
