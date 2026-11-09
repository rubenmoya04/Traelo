# Tráelo

Trae un repo de GitHub al móvil Android y lo deja listo para abrirlo en el editor de Godot para Android.

Nació de una molestia concreta: para probar un proyecto de Godot en el móvil había que entrar en la web de GitHub, descargar el zip, descomprimirlo y repetirlo en cada cambio. Tráelo hace solo lo que cambió.

Solo baja. No hace push, no hace commits y no escribe nada en GitHub.

## Qué hace

- Lista tus repos (propios y de otros donde eres colaborador) y marca de cada uno si estás al día o hay cambios.
- Eliges la rama y pulsas **Traer cambios**. Solo descarga los archivos que cambiaron y borra los que ya no están en la rama.
- Favoritos arriba, con **Traer** y **Abrir en Godot** a mano.
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

## Cómo funciona

Compara el árbol del repo en GitHub con lo que se trajo la última vez, usando el hash de cada archivo (el mismo SHA-1 de blob que usa git). Descarga en paralelo solo lo distinto, verifica cada archivo antes de guardarlo y lo escribe de forma atómica. Si hay muchos archivos que bajar (la primera vez o una tanda grande de cambios) usa el zip de GitHub, una sola descarga, y saca de él solo lo que hace falta. La lista de archivos traídos se guarda fuera de la carpeta del proyecto, para que Godot no la vea, y nunca se toca `.godot`.

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

Lo ignorado que ya estaba en el móvil no se borra: solo deja de actualizarse.

## Estructura

- `core/`: el motor, en Kotlin sin dependencias de Android y con tests.
- `app/`: interfaz (Jetpack Compose, Material 3) y lo propio de Android: lanzar Godot, permisos, cifrado del token.

## Compilar

Hace falta JDK 17 y el SDK de Android (plataforma 35).

```bash
./gradlew :core:test           # tests del motor
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
```

## Limitaciones

- Solo se ha probado con repos de Godot en un par de móviles Android.
- La firma de `app/debug.keystore` es pública a propósito, para que las APK de CI se instalen unas encima de otras. Instala solo las APK de la Release de este repo, y no uses esta firma para publicar en Play Store.
- No gestiona submódulos ni Git LFS: los punteros de LFS se bajan como texto.
