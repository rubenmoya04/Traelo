# Tráelo

Trae un repo de GitHub al móvil Android y lo deja listo para abrirlo en el editor de Godot para Android.

Nació de una molestia concreta: para probar un proyecto de Godot en el móvil había que entrar en la web de GitHub, descargar el zip, descomprimirlo y repetirlo en cada cambio. Tráelo hace solo lo que cambió.

Solo baja. No hace push, no hace commits y no escribe nada en GitHub.

## Qué hace

- Lista tus repos (propios y de otros donde eres colaborador) y marca de cada uno si estás al día o hay cambios.
- **Traer y abrir en Godot**: un toque baja lo nuevo y abre el editor. Si ya estabas al día, solo pregunta a GitHub (una petición) y abre.
- Eliges la rama y pulsas **Traer cambios**. Solo descarga los archivos que cambiaron y borra los que ya no están en la rama.
- Progreso de verdad: archivos, MB, velocidad y tiempo que queda. Se puede cancelar cuando quieras.
- Nunca pisa en silencio lo que cambiaste en el móvil: lo guarda aparte y te avisa (ver **Conflictos**).
- Favoritos arriba, con **Traer y abrir en Godot** y **Abrir en Godot** a mano.
- La lista de repos sale al momento (se guarda en el móvil) y se actualiza por detrás.
- **Abrir en Godot** lanza el editor. Abrir el proyecto directamente es experimental y depende de la versión del editor; si no funciona, se abre el gestor de proyectos y lo importas una vez.
- En **Avanzado**: solo comprobar, traer, restaurar los archivos del móvil a como están en GitHub, elegir la carpeta con el navegador de archivos y borrar la copia entera del móvil.

## Instalación

1. Abre la Release [`ultima`](../../releases/tag/ultima) desde el móvil y baja `traelo.apk`.
2. Android pedirá permiso para instalar apps desde esa fuente.
3. Abre Tráelo, pega tu token y concede el permiso de archivos.

Las versiones nuevas se instalan encima de la anterior. Cada commit en `main` publica una APK nueva en esa Release.

## Token de GitHub

Cada persona crea el suyo. Tráelo solo necesita leer.

- **Repos propios:** token fine-grained con *All repositories* (o los que quieras) y el permiso **Contents: Read-only**.
- **Repos de otra cuenta donde eres colaborador:** los fine-grained solo ven los repos de la cuenta que los crea, así que hace falta un token *classic* con el permiso `repo`.

El token se guarda cifrado con el Keystore de Android y solo se envía a `api.github.com`.

## Permisos

- **Acceso a todos los archivos:** el editor de Godot tiene que leer la carpeta donde Tráelo deja el proyecto, así que no vale el almacenamiento privado de la app.
- **Internet:** para hablar con la API de GitHub.

## Conflictos: si cambias archivos en el móvil

Godot a veces toca archivos al abrir el proyecto (`project.godot`, algún `.import`) y a lo mejor tú también editas algo en el móvil. Tráelo es solo de bajada, así que manda lo de GitHub, pero nunca se pierde lo tuyo sin avisar:

- Si un archivo cambió en GitHub **y** en el móvil, antes de traer el nuevo mueve el tuyo a una carpeta de conflictos.
- Si se borró en GitHub pero en el móvil lo habías cambiado, también va a esa carpeta en vez de borrarse.
- Si en el móvil había un archivo tuyo justo donde llega uno nuevo, igual.
- **Restaurar** también guarda una copia de lo que repone.
- Si en GitHub no cambió, tu versión se queda donde está (eso solo lo cambia **Restaurar**).

La carpeta está **al lado** del proyecto, no dentro, para que Godot no la vea:

```
Documents/Traelo/Socks-Sauce/                         <- el proyecto
Documents/Traelo/Socks-Sauce-conflictos/2026-11-10_18-30-05/project.godot
```

La app te dice cuántos archivos guardó, cuáles y en qué carpeta. Con **Traer y abrir en Godot**, si hubo conflictos no abre Godot hasta que lo veas y pulses **Abrir en Godot igualmente**. Al comprobar, ya avisa antes de traer de cuántos archivos tuyos se guardarán aparte. Borrar la copia del móvil no toca la carpeta de conflictos.

## Cómo funciona

- **Salida rápida:** primero pregunta solo por el último commit de la rama, con `If-None-Match` (ETag). Si no cambió, GitHub contesta 304, que no gasta cuota, y si todos los archivos siguen en su sitio ni siquiera pide el árbol.
- **Un solo árbol:** si hay cambios pide el árbol entero de la rama en una petición y lo compara en memoria con lo que se trajo la última vez, usando el hash de cada archivo (el mismo SHA-1 de blob que usa git). Solo calcula hashes del móvil para los archivos que cambiaron, y en paralelo.
- **Descargas en paralelo:** hasta 24 a la vez por un único cliente HTTP compartido (OkHttp: keep-alive, HTTP/2 con `api.github.com`, gzip). Cada archivo se escribe a trozos en un temporal con buffers grandes, el SHA-1 se comprueba mientras se escribe (sin volver a leerlo) y solo entonces sustituye al viejo. Los cortes de red se reintentan.
- **Zip cuando compensa:** compara lo que costaría archivo a archivo con lo que cuesta el zip de la rama entera y elige. En la práctica, zip la primera vez o cuando cambia casi todo (y siempre por encima de 700 archivos, para no pasar del límite de GitHub de unas 900 peticiones por minuto). El zip se lee mientras llega, sin guardarlo entero, y se sacan solo los archivos que hacen falta. Si el zip se corta, lo que falte se baja suelto.
- **Una sincronización por repo a la vez:** si pulsas otra vez, espera su turno.
- **El estado se guarda al final:** la lista de archivos traídos, el commit y la rama se escriben de una vez, de forma atómica, solo cuando todo salió bien. Si se corta a mitad, la siguiente vez reconoce por su hash lo que ya llegó y sigue donde lo dejó.

Esa lista se guarda fuera de la carpeta del proyecto, para que Godot no la vea, y nunca se toca `.godot`.

### Velocidad

Medido con `./gradlew :core:benchmark`: un repo sintético tipo juego de Godot (3151 archivos, 80 MB de PNG, OGG, `.import` y scripts) y una red simulada de 120 ms por petición y 8 MB/s. La red es de mentira, así que los números sirven para comparar versiones, no para prometer tiempos en tu Wi-Fi.

| Caso | Antes (0.4) | Ahora (0.5) |
|---|---:|---:|
| Primera descarga | 13,4 s | 12,6 s |
| Traer sin cambios | 0,32 s, 2 peticiones | 0,13 s, 1 petición (304 en GitHub) |
| 40 archivos cambiados | 0,99 s | 0,63 s |
| 150 archivos cambiados | 12,3 s (zip entero, 79 MB) | 1,2 s (3,6 MB) |
| 600 archivos cambiados | 12,4 s (zip entero, 79 MB) | 3,7 s (12 MB) |
| Buscar cambios locales (hash de todo) | 0,17 s | 0,09 s |

La 0.4 bajaba el zip entero en cuanto cambiaban 60 archivos; ahora decide con números. Con `-PbenchArgs="120 8 crossover parallel"` saca también el cruce zip contra archivos sueltos y cuánto ayuda la concurrencia.

### Ignorar carpetas: `.traeloignore`

Si el repo tiene un archivo `.traeloignore` en la raíz, Traelo no baja lo que diga, por ejemplo los tests o las herramientas que no hacen falta para jugar. Una regla por línea; `#` para comentarios:

```
# solo para jugar en el movil
tests/
tools/
docs/
*.md
```

- `carpeta/` ignora esa carpeta entera desde la raíz.
- `*.md` ignora por nombre en cualquier carpeta.
- Un asterisco vale cualquier nombre dentro de una carpeta y dos seguidos, cualquier profundidad.

Qué pasa con lo ignorado que ya estaba en el móvil:

- Si lo trajo Tráelo y no lo has tocado, se borra al traer cambios. Igual si ya no existe en la rama.
- Si lo trajo Tráelo pero lo cambiaste en el móvil, va a la carpeta de conflictos (ver **Conflictos**) y te avisa.
- Lo que Tráelo no trajo (tuyo o de Godot, como los `.uid`) no se borra nunca. Tampoco se toca nada de `.godot`.
- Las carpetas que se quedan vacías se quitan.
- Si viene de una versión vieja que no apuntaba la huella de esos archivos, no se borran a ciegas. La app dice cuántos archivos ignorados siguen en el móvil y te ofrece **Quitar lo ignorado**, que los mueve a la carpeta de conflictos (no los borra), con confirmación. Con **Traer y abrir en Godot** espera a que lo veas, para que Godot no se queje de scripts viejos.

## Estructura

- `core/`: el motor, en Kotlin sin dependencias de Android y con tests.
- `app/`: interfaz (Jetpack Compose, Material 3) y lo propio de Android: lanzar Godot, permisos, cifrado del token.

## Compilar

Hace falta JDK 17 y el SDK de Android (plataforma 35).

```bash
./gradlew :core:test           # tests del motor
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:benchmark      # medir la sincronización (red simulada)
```

Única dependencia nueva en el motor: [OkHttp](https://square.github.io/okhttp/) (Apache 2.0, gratis).

## Limitaciones

- Solo se ha probado con repos de Godot en un par de móviles Android.
- La firma de `app/debug.keystore` es pública a propósito, para que las APK de CI se instalen unas encima de otras. Instala solo las APK de la Release de este repo, y no uses esta firma para publicar en Play Store.
- No gestiona submódulos ni Git LFS: los punteros de LFS se bajan como texto.
