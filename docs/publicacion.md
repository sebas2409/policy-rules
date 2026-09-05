# Publicación

La librería se publica en **Maven Central**, que es el único registro con
consumo anónimo: quien la use no necesita token ni declarar repositorios.

- [Puesta en marcha](#puesta-en-marcha) — solo la primera vez
- [El flujo de release](#el-flujo-de-release)
- [Qué hace cada workflow](#qué-hace-cada-workflow)
- [Consumir la librería](#consumir-la-librería)
- [Problemas frecuentes](#problemas-frecuentes)

---

## Puesta en marcha

Cuatro pasos manuales, una sola vez. Hasta completarlos el workflow `Release`
avisa y no publica nada.

### 1. Verificar el namespace `io.github.sebas2409`

1. Entra en <https://central.sonatype.com> con la cuenta de GitHub.
2. *Publish → Namespaces → Add Namespace* → `io.github.sebas2409`.
3. El portal da un código tipo `abc123xyz`. Crea un repositorio público vacío
   con ese nombre exacto en tu cuenta y pulsa *Verify Namespace*.
4. Cuando quede en **verified**, borra ese repositorio: ya no hace falta.

El namespace `io.github.<usuario>` se verifica así, sin dominio propio. Es la
razón por la que el `groupId` es `io.github.sebas2409` y no `com.policyrules`.

### 2. Generar el token del portal

En *Account → Generate User Token*. Devuelve un usuario y una contraseña que no
son los de tu cuenta: son las credenciales que usa Maven.

### 3. Crear la clave GPG

Central exige que cada artefacto vaya firmado.

```bash
# Crear la clave (elige RSA 4096 y una passphrase que puedas guardar)
gpg --full-generate-key

# Anotar el id largo de la clave
gpg --list-secret-keys --keyid-format=long

# Publicarla, o Central no podrá verificar la firma
gpg --keyserver keyserver.ubuntu.com --send-keys TU_KEY_ID

# Exportarla para el workflow (bloque completo, con las líneas BEGIN/END)
gpg --armor --export-secret-keys TU_KEY_ID
```

Guarda la clave privada y la passphrase en un gestor de contraseñas: perderlas
obliga a repetir el proceso.

### 4. Registrar los cuatro secretos en GitHub

En *Settings → Secrets and variables → Actions → New repository secret*:

| Secreto | Contenido |
|---------|-----------|
| `CENTRAL_USERNAME` | usuario del token del portal (paso 2) |
| `CENTRAL_TOKEN` | contraseña de ese token |
| `GPG_PRIVATE_KEY` | la salida completa de `gpg --armor --export-secret-keys` |
| `GPG_PASSPHRASE` | la passphrase de la clave |

---

## El flujo de release

**La versión del `pom.xml` manda.** No se crean tags a mano: el tag se deriva de
la versión.

```bash
# 1. Subir la versión en pom.xml (ej. 1.0.0 -> 1.1.0)
#    <version>1.1.0</version>

git commit -am "Versión 1.1.0"
git push origin main
```

A partir de ahí, el workflow `Release` hace solo:

```
pom.xml (1.1.0)
   ├─ compila, pasa los tests y genera fuentes + JavaDoc
   ├─ firma los cinco artefactos con GPG
   ├─ publica io.github.sebas2409:policy-rules:1.1.0 en Maven Central
   ├─ crea el tag v1.1.0
   └─ abre la release en GitHub con los tres jar adjuntos
```

Reglas del portero, para que nada se publique dos veces ni por error:

| Situación | Resultado |
|-----------|-----------|
| falta alguno de los cuatro secretos | avisa y no publica |
| versión `1.1.0-SNAPSHOT` | no hace nada (los snapshots no se publican) |
| versión `1.1.0` con el tag `v1.1.0` ya existente | no hace nada |
| versión `1.1.0` sin tag | se publica |

Si se lanza a mano (*Actions → Release → Run workflow*) y algo de eso falla, el
workflow **falla** en vez de terminar en silencio: si lo has lanzado tú,
esperabas una publicación.

El tag se crea **después** de que Central acepte el artefacto, así que un tag
siempre corresponde a una versión que existe de verdad en el registro. Con
`autoPublish` no hay que pulsar nada en el portal.

Una versión publicada en Central es **inmutable**: no se puede borrar ni
sobrescribir. Para corregir algo se publica una versión nueva.

La primera vez, el artefacto tarda unos minutos en aparecer en
[central.sonatype.com](https://central.sonatype.com/artifact/io.github.sebas2409/policy-rules)
y hasta unas horas en el índice de búsqueda.

---

## Qué hace cada workflow

### `.github/workflows/ci.yml`

Se ejecuta en cada push a `main` y en cada pull request. Ejecuta
`mvn -Prelease verify`: compila, pasa los tests y **genera el JavaDoc con
`doclint` estricto**. Así una documentación incompleta rompe el PR y no la
publicación. No firma ni necesita secretos.

### `.github/workflows/release.yml`

Se ejecuta cuando cambia `pom.xml` en `main`, o a mano. Necesita
`contents: write` para crear el tag y la release, y los cuatro secretos de
arriba. Publica con `mvn -Prelease,central deploy`.

### Los perfiles del `pom.xml`

| Perfil | Qué añade | Quién lo usa |
|--------|-----------|--------------|
| `release` | jar de fuentes y de JavaDoc | CI, el release y tú en local |
| `central` | firma GPG y `central-publishing-maven-plugin` | solo el release |

Están separados a propósito: así `mvn -Prelease verify` sigue funcionando en
local sin tener una clave GPG configurada.

---

## Consumir la librería

Sin token, sin repositorios extra:

```xml
<dependency>
    <groupId>io.github.sebas2409</groupId>
    <artifactId>policy-rules</artifactId>
    <version>1.0.0</version>
</dependency>
```

```kotlin
implementation("io.github.sebas2409:policy-rules:1.0.0")
```

El proyecto consumidor necesita **Java 25**.

---

## Problemas frecuentes

**`401 Unauthorized` al publicar.** Las credenciales son las del *user token*
del portal, no el usuario y contraseña de la cuenta. Regenéralas en
*Account → Generate User Token*.

**`403` o "namespace not verified".** El namespace `io.github.sebas2409` no ha
terminado de verificarse, o el `groupId` del pom no coincide exactamente con él.

**"No public key" o falla la validación de la firma.** La clave GPG no está
publicada en un keyserver. Repite `gpg --send-keys`; la propagación tarda unos
minutos.

**`gpg: signing failed: Inappropriate ioctl for device`.** Falta el
`--pinentry-mode loopback` que el pom ya configura, o la passphrase no llega por
`MAVEN_GPG_PASSPHRASE`.

**El workflow no se dispara.** Solo escucha cambios en `pom.xml` dentro de
`main`. Si has cambiado la versión en otra rama, mergea a `main` o lánzalo a
mano.

**La build falla en el JavaDoc.** Es intencionado: el perfil `release` usa
`doclint` estricto. Completa la documentación del elemento que señala el error.

**Publicar desde local.** Es posible, aunque el flujo previsto es el workflow.
Necesitas el `server` `central` en tu `settings.xml` y la clave GPG en el
llavero:

```bash
mvn -B -Prelease,central deploy
```
