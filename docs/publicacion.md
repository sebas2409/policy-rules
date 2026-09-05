# Publicación

Cómo se publica la librería y cómo se consume desde otro proyecto.

- [El flujo de release](#el-flujo-de-release)
- [Qué hace cada workflow](#qué-hace-cada-workflow)
- [Consumir desde GitHub Packages](#consumir-desde-github-packages)
- [Publicar en Maven Central](#publicar-en-maven-central)
- [JitPack, la alternativa sin token](#jitpack-la-alternativa-sin-token)
- [Problemas frecuentes](#problemas-frecuentes)

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
   ├─ publica com.policyrules:policy-rules:1.1.0 en GitHub Packages
   ├─ crea el tag v1.1.0
   └─ abre la release v1.1.0 con los tres jar adjuntos
```

Reglas del portero, para que nada se publique dos veces ni por error:

| Versión del pom | Resultado |
|-----------------|-----------|
| `1.1.0`, sin tag `v1.1.0` | se publica |
| `1.1.0`, con tag `v1.1.0` ya existente | no hace nada (esa versión ya salió) |
| `1.1.0-SNAPSHOT` | no hace nada (los snapshots no se publican) |

Si se lanza a mano (`Actions → Release → Run workflow`) y la versión no es
publicable, el workflow **falla** en vez de terminar en silencio: si lo has
lanzado tú, esperabas una publicación.

El tag se crea **después** de publicar el artefacto, así que un tag siempre
corresponde a una versión que existe de verdad en el registro.

---

## Qué hace cada workflow

### `.github/workflows/ci.yml`

Se ejecuta en cada push a `main` y en cada pull request. Ejecuta
`mvn -Prelease verify`, es decir: compila, pasa los tests y **genera el JavaDoc
con `doclint` estricto**. Así una documentación incompleta rompe el PR y no la
publicación.

### `.github/workflows/release.yml`

Se ejecuta cuando cambia `pom.xml` en `main`, o a mano. Necesita estos permisos,
que ya están declarados en el propio workflow:

- `contents: write` para crear el tag y la release,
- `packages: write` para publicar el artefacto.

No hace falta configurar ningún secreto: usa el `GITHUB_TOKEN` que Actions
inyecta solo. La URL del registro se construye con `${{ github.repository }}`, de
modo que el workflow funciona igual en un fork o si renombras el repositorio.

---

## Consumir desde GitHub Packages

En el proyecto que use la librería:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/sebas2409/policy-rules</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.policyrules</groupId>
        <artifactId>policy-rules</artifactId>
        <version>1.0.0</version>
    </dependency>
</dependencies>
```

> **GitHub Packages exige autenticación incluso para leer.** Es su única
> limitación relevante: aunque el repositorio sea público, quien consuma el
> paquete necesita un token. Si necesitas consumo anónimo, ve a
> [Maven Central](#publicar-en-maven-central) o a [JitPack](#jitpack-la-alternativa-sin-token).

En `~/.m2/settings.xml` del consumidor, con un token clásico que tenga el permiso
`read:packages`:

```xml
<settings>
    <servers>
        <server>
            <id>github</id>
            <username>TU_USUARIO</username>
            <password>${env.GITHUB_TOKEN}</password>
        </server>
    </servers>
</settings>
```

En Gradle:

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/sebas2409/policy-rules")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("com.policyrules:policy-rules:1.0.0")
}
```

Y si el consumidor es otro workflow de Actions, no hace falta ningún secreto
extra: `secrets.GITHUB_TOKEN` ya sirve para leer paquetes de la misma
organización.

---

## Publicar en Maven Central

Es el único registro con consumo realmente anónimo. Requiere tres cosas que
GitHub Packages no pide:

**1. Un `groupId` verificado.** `com.policyrules` obliga a demostrar que
controlas el dominio `policyrules.com`. Si no lo tienes, el camino habitual es
cambiar el `groupId` a `io.github.TU_USUARIO`, que se verifica creando un
repositorio con un nombre que te indica el portal. Eso implica renombrar también
el paquete base, o dejar el paquete Java como está y cambiar solo las coordenadas
Maven (es legal, aunque desaconsejado por convención).

**2. Firma GPG de los artefactos.** Se añade al perfil `release`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-gpg-plugin</artifactId>
    <version>3.2.7</version>
    <executions>
        <execution>
            <id>sign-artifacts</id>
            <phase>verify</phase>
            <goals><goal>sign</goal></goals>
        </execution>
    </executions>
</plugin>
```

**3. El plugin del Central Portal**, que sustituye al `deploy` de este workflow:

```xml
<plugin>
    <groupId>org.sonatype.central</groupId>
    <artifactId>central-publishing-maven-plugin</artifactId>
    <version>0.7.0</version>
    <extensions>true</extensions>
    <configuration>
        <publishingServerId>central</publishingServerId>
    </configuration>
</plugin>
```

En el workflow, el paso de publicación pasa a ser:

```yaml
      - name: Preparar Java 25, GPG y las credenciales de Central
        uses: actions/setup-java@v4
        with:
          java-version: '25'
          distribution: temurin
          cache: maven
          server-id: central
          server-username: MAVEN_USERNAME
          server-password: MAVEN_TOKEN
          gpg-private-key: ${{ secrets.GPG_PRIVATE_KEY }}
          gpg-passphrase: MAVEN_GPG_PASSPHRASE

      - name: Publicar en Maven Central
        env:
          MAVEN_USERNAME: ${{ secrets.CENTRAL_USERNAME }}
          MAVEN_TOKEN: ${{ secrets.CENTRAL_TOKEN }}
          MAVEN_GPG_PASSPHRASE: ${{ secrets.GPG_PASSPHRASE }}
        run: mvn -B -Prelease deploy
```

Secretos necesarios: `CENTRAL_USERNAME`, `CENTRAL_TOKEN` (los genera el portal de
Sonatype), `GPG_PRIVATE_KEY` y `GPG_PASSPHRASE`. El resto del workflow —leer la
versión, el portero, el tag y la release— no cambia.

Recuerda además rellenar el `TODO` del `pom.xml` (`url`, `scm`) y añadir un
bloque `<developers>`: Central los exige.

---

## JitPack, la alternativa sin token

JitPack compila el repositorio a partir del tag y sirve el artefacto sin
autenticación. No hace falta publicar nada: basta con que exista el tag que ya
crea este workflow.

El consumidor añade:

```xml
<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>

<dependency>
    <groupId>com.github.sebas2409</groupId>
    <artifactId>policy-rules</artifactId>
    <version>v1.0.0</version>
</dependency>
```

Como los constructores de JitPack no traen Java 25 por defecto, hace falta un
`jitpack.yml` en la raíz del repositorio:

```yaml
before_install:
  - sdk install java 25-open
  - sdk use java 25-open
```

Es la opción más barata para consumo público, a cambio de depender de un servicio
de terceros y de que la primera descarga de cada versión tarde lo que tarde la
compilación.

---

## Problemas frecuentes

**`409 Conflict` al publicar.** GitHub Packages no deja sobrescribir una versión
ya publicada. Sube la versión del pom; el portero del workflow evita llegar aquí,
salvo que se haya borrado el tag a mano.

**El workflow no se dispara.** Solo escucha cambios en `pom.xml` dentro de
`main`. Si has cambiado la versión en otra rama, todavía no ha pasado nada;
mergea a `main` o lánzalo a mano.

**`401 Unauthorized` al consumir.** Falta el `server` con `id` `github` en el
`settings.xml` del consumidor, o el token no tiene `read:packages`. El `id` del
`<repository>` y el del `<server>` tienen que coincidir.

**La build falla en el JavaDoc.** Es intencionado: el perfil `release` usa
`doclint` estricto. Completa la documentación del elemento que señala el error.

**Publicar desde local.** Es posible, aunque el flujo previsto es el workflow:

```bash
mvn -B -Prelease deploy \
    -DaltDeploymentRepository="github::https://maven.pkg.github.com/sebas2409/policy-rules"
```

con el `server` `github` configurado en tu `settings.xml`.
