# Tráelo

App Android privada de Rubén y Silvia para traer un repo de GitHub al móvil y abrirlo en el editor de Godot para Android. Solo baja: no hay push ni commits desde el móvil.

## Qué hace

- Lista tus repos y dice de cada uno si estás al día o desactualizado.
- Eliges la rama y pulsas **Traer cambios**. Solo baja lo que cambió y borra lo que ya no está en la rama.
- **Abrir en Godot** lanza el editor (abrir directamente el proyecto es experimental).
- En **Avanzado**: solo comprobar (fetch), traer (pull) y restaurar los archivos del móvil a como están en GitHub.

## Cómo instalarla

1. En el móvil, abre la Release **ultima** de este repo (pestaña Releases en GitHub) y baja `traelo.apk`.
2. Android pedirá permiso para instalar apps de esa fuente. Dáselo.
3. Abre Tráelo y sigue los tres pasos de bienvenida.

Las siguientes versiones se instalan encima, sin desinstalar nada (llevan siempre la misma firma de prueba).

## El token de GitHub

Cada persona crea el suyo: GitHub, Settings, Developer settings, Fine-grained personal access tokens. Permiso **Contents: solo lectura** y elige los repos que quieras traer. Se guarda cifrado en el móvil.

## Cómo está hecha

- `core/`: el motor (Kotlin sin Android, con tests). Compara la huella de cada archivo con la que se trajo la última vez.
- `app/`: la interfaz (Jetpack Compose) y lo propio de Android.
- Todo gratis: Kotlin, Gradle y GitHub Actions.
- `app/debug.keystore` es una clave de prueba pública. Vale para instalar en vuestros móviles, no para publicar en Play Store.

## Comandos

```bash
./gradlew :core:test        # tests del motor
./gradlew :app:assembleDebug  # genera app/build/outputs/apk/debug/app-debug.apk
```
