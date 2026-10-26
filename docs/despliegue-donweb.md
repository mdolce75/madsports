# Despliegue en DonWeb, paso a paso

Mad Sports corre en un servidor con Docker: la app, PostgreSQL y Caddy (HTTPS automático para madsports.com.ar,
rinkreports.com.ar y rinkmetrics.com.ar). Todo lo necesario está en `Dockerfile` y en la carpeta `deploy/`.

Tiempo estimado: 1 hora la primera vez (más lo que tarde el DNS en propagarse).

## 1. Contratar el servidor

En DonWeb, **Cloud Server**:

- **Plan:** 2 vCPU, 8 GB de RAM, 20 GB NVMe (o 4 vCPU, 8 GB, 30 GB si la diferencia de precio es poca).
- **Sistema:** Ubuntu 24.04 LTS.
- **Acceso:** si te deja cargar una clave SSH, mejor; si no, te da una clave de `root` por mail o en el panel.
- **Backups / snapshots:** si los ofrece, activalos (además de los respaldos del paso 9).

Anotá la **IP pública** del servidor (por ejemplo `200.58.100.20`).

## 2. Apuntar los dominios al servidor (DNS en DonWeb)

En **Mis Dominios → Nameservers y Zona DNS** de cada dominio (madsports.com.ar, rinkreports.com.ar,
rinkmetrics.com.ar):

| Tipo | Nombre | Contenido |
|---|---|---|
| `A` | el dominio (`madsports.com.ar`) | la IP del servidor (reemplaza a la provisoria `192.0.2.1`) |
| `CNAME` | `www.madsports.com.ar` | `madsports.com.ar` |

Lo mismo en los otros dos con su nombre. **No toques** los registros de Resend (`resend._domainkey`, `send`, `rsend`,
`_dmarc`) ni los de Google (`MX`, `google-site-verification`).

Para ver si ya propagó (desde tu computadora): `nslookup madsports.com.ar` tiene que mostrar la IP del servidor.

## 3. Entrar al servidor

Desde la terminal de tu computadora (en Windows, PowerShell):

```bash
ssh root@IP_DEL_SERVIDOR
```

## 4. Preparar el servidor

```bash
apt update && apt upgrade -y
# Firewall: solo SSH y la web
ufw allow OpenSSH && ufw allow 80 && ufw allow 443 && ufw --force enable
# Docker
curl -fsSL https://get.docker.com | sh
# Memoria de intercambio de 2 GB (por si algún pico de memoria)
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
```

## 5. Bajar la aplicación

```bash
git clone https://github.com/mdolce75/madsports.git /opt/madsports
cd /opt/madsports/deploy
chmod +x respaldo.sh actualizar.sh
```

## 6. Cargar las variables

```bash
cp .env.example .env
nano .env
```

Completá:

- `DB_PASSWORD`: una clave al azar. Generala con `openssl rand -base64 24` y pegala.
- `SMTP_PASSWORD`: la API key de Resend (la misma que usás en tu `.env` local).
- `REMEMBER_ME_KEY`: otra clave al azar (`openssl rand -base64 32`).
- `GEMINI_API_KEY` y `ALERTS_EMAIL`: opcionales.

Guardar en nano: `Ctrl+O`, `Enter`, `Ctrl+X`. Este archivo **no se sube nunca** al repositorio.

## 7. Levantar todo

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

La primera vez tarda unos minutos (compila la app). Para ver cómo arranca:

```bash
docker compose -f docker-compose.prod.yml logs -f app
```

Cuando aparezca `Started MadSportsApplication`, salir con `Ctrl+C` (la app sigue corriendo).

## 8. Probar

- https://madsports.com.ar, https://rinkreports.com.ar y https://rinkmetrics.com.ar: cada uno con su sitio y el
  candado de HTTPS (si el DNS todavía no propagó, Caddy reintenta solo; puede tardar unos minutos).
- Registrarse con un mail tuyo: tiene que llegar el mail de confirmación y el enlace llevar al dominio del producto.
- Estado de la app: `curl -s localhost:80 -o /dev/null -w "%{http_code}\n"` y
  `docker compose -f docker-compose.prod.yml ps` (los tres servicios `Up`).

La base arranca vacía (las migraciones crean todo). La primera noche, a las 5:00, la precarga baja las actas de
SIDGAD (unos 4 minutos); hasta entonces, el primer informe de club tarda ~70 s.

## 9. Respaldos diarios

```bash
crontab -e
```

Agregar la línea:

```
30 3 * * * /opt/madsports/deploy/respaldo.sh >> /var/log/madsports-respaldo.log 2>&1
```

Cada día a las 3:30 guarda la base y los informes en `/var/backups/madsports` (los últimos 14 días). Conviene bajarlos
de vez en cuando a tu computadora (están en el mismo servidor):

```bash
scp root@IP_DEL_SERVIDOR:/var/backups/madsports/base-*.sql.gz .
```

## 10. Actualizar cuando haya cambios en main

```bash
/opt/madsports/deploy/actualizar.sh
```

Baja lo último, reconstruye y reinicia la app (la base y los informes no se tocan; las migraciones corren solas).

## Comandos útiles

| Para | Comando (desde `/opt/madsports/deploy`) |
|---|---|
| Ver el estado | `docker compose -f docker-compose.prod.yml ps` |
| Ver los logs de la app | `docker compose -f docker-compose.prod.yml logs --tail 200 app` |
| Reiniciar la app | `docker compose -f docker-compose.prod.yml restart app` |
| Entrar a la base | `docker compose -f docker-compose.prod.yml exec db psql -U madsports madsports` |
| Restaurar un respaldo | `gunzip -c /var/backups/madsports/base-FECHA.sql.gz \| docker compose -f docker-compose.prod.yml exec -T db psql -U madsports madsports` (sobre una base vacía) |
