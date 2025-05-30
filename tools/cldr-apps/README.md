# CLDR Survey Tool

For general information, see the main [README.md](../../README.md).

The `cldr-apps` subproject builds `cldr-apps.war` which contains the Survey Tool
packaged for deployment. The Survey Tool is used to collect and confirm translations
for CLDR.


## DB setup

- Setup MySQL or MariaDB
- Create a database (schema), named `cldrdb` with codepage `latin1` and collation `latin1_bin` (Yes, ironic)

```sql
CREATE SCHEMA `cldrdb` DEFAULT CHARACTER SET latin1 COLLATE latin1_bin ;
```

- Create a user `surveytool`

```sql
create user 'surveytool'@'localhost' IDENTIFIED BY 'your_strong_password';
```

- Grant the user `surveytool` “all” privileges

```sql
GRANT ALL PRIVILEGES ON cldrdb.* TO 'surveytool'@'localhost';
```

## Building and Running the Survey Tool

Please use the parent [CLDR/tools/pom.xml](../pom.xml) with maven to build and run.

- Copy `src/main/liberty/config/server.env.sample` to `src/main/liberty/config/server.env`
- Edit that `server.env` file to contain the MySQL credentials for the ST database (from above).

```ini
MYSQL_USER=surveytool
MYSQL_PASSWORD=your_strong_password
MYSQL_DB=cldrdb
MYSQL_PORT=3306
MYSQL_HOST=localhost
```

- Use `mvn --file=tools/pom.xml -DskipTests=true -pl cldr-apps liberty:dev` to run a development
web server, listening on port 9080. Hit control-C to cancel the server.

- Navigate to http://localhost:9080/cldr-apps to view the app

- See [Configuration](#configuration) below. You will need to restart the server after edit.

See <https://cldr.unicode.org/development/running-survey-tool> for out of date information
about the Survey Tool.

## Using Logging

This is interim guidance, to be expanded upon as part of [CLDR-8581](https://unicode-org.atlassian.net/browse/CLDR-8581)

### Getting a logger

Example:

```java
class MyClass {
    static final java.util.logging.Logger logger = SurveyLog.forClass(MyClass.class);
    // …
    MyClass() {
        logger.finer("A finer point.");  // see java.util.logging.Logger docs
        logger.info("Something informative!");
        logger.warning("Something bad happened!");
    }
}
```

### Configuring log

In `bootstrap.properties` _or other mechanism for setting system properties_, set the following to bump the log level:

```properties
com.ibm.ws.logging.trace.specification=org.unicode.cldr.web.MyClass.level=finest
```

You can also set in the `server.xml`:

```xml
<logging traceSpecification="org.unicode.cldr.web.MyClass.level=finest"/>
```

See <https://openliberty.io/docs/21.0.0.4/log-trace-configuration.html>

## Testing SMTP

I use [mailhog](https://github.com/mailhog/MailHog). From docker:

```shell
docker run --rm -p 8025:8025 -p 1025:1025 mailhog/mailhog
```

Then browse to http://localhost:8025 to watch mail flow in.

Then, setup SurveyTool with these `cldr.properties` (see [Configuration](#configuration))

```properties
CLDR_SENDMAIL=true
mail.host=127.0.0.1
mail.smtp.port=1025

## if needed
## etc see
#CLDR_SMTP_USER=authuser
#CLDR_SMTP_PASSWORD=authpassword
#mail.smtp.auth=true
#mail.smtp.starttls.enable=true
# see <https://javaee.github.io/javamail/docs/api/com/sun/mail/smtp/package-summary.html>

## other properties
## how long after ST startup before we start trying to send out mail
#CLDR_MAIL_DELAY_FIRST=55
## how many seconds to wait between each "batch"
#CLDR_MAIL_DELAY_EACH=15
## How many mails to send in each "batch"
#CLDR_MAIL_BATCHSIZE=0
## How long to wait between each mail in the batch.
CLDR_MAIL_DELAY_BATCH_ITEM=0
```

### Configuration

SurveyTool is configured with a `cldr.properties` file which is created on first startup. It must be edited before
SurveyTool can start working, to remove the `CLDR_MAINTENANCE=true` line.

You will also likely want to change the `CLDR_DIR` property in that file to point to your CLDR root, otherwise a new CLDR root will be checked out.

Search for this file in your Java workspace after launching - it may be in a random place. See [Advanced Configuration](#advanced-configuration) below for how to move this directory. On one system the cldr directory was in `tools/cldr-apps/target/liberty/wlp/usr/servers/cldr/cldr`.  On the production and staging servers, the location is `/srv/st/config`


#### Advanced Configuration

- There is the file `tools/cldr-apps/src/main/liberty/config/jvm.options` which supplies the default memory settings for SurveyTool, this file is checked in.

- The 'CLDR Home' directory as mentioned above is normally in a random location determined by the server. To fix its location, create a file `tools/cldr-apps/src/main/liberty/config/bootstrap.properties` with the following:

```ini
org.unicode.cldr.util.CLDRConfigImpl.cldrHome=/Users/srl295/src/cldr-st/config
```

You will also want to make sure this directory exists and is writeable. You can move the existing `cldr.properties` and other files to that directory.

## Docker Testing

These instructions set you up to use [Docker](https://docker.io) to execute a complete local test of the Survey Tool. The advantage is that you don't have to manually configure any web servers, databases, etc.

Note: if you are on an Apple Silicon or other systems, you might need to set this variable:

`export DOCKER_DEFAULT_PLATFORM=linux/amd64`

### Option A: Local build of a Server

This command may be somewhat excessive in terms of what it does, but it works.

TODO CLDR-14409: We should document how to run these steps against a development server (liberty:dev)

```shell
# From the top level CLDR dir
# clean out webpack build (this tends to get large when using webpack development mode!)
rm -rf tools/cldr-apps/src/main/webapp/dist ./tools/cldr-apps/target/cldr-apps/dist/
# build
mvn --file=tools/pom.xml clean compile package install -DskipTests=true
# create server package
mvn -pl cldr-apps liberty:create liberty:deploy liberty:package -Dinclude=usr --file tools/pom.xml -DskipTests=true
```

The output file is `tools/cldr-apps/target/cldr-apps.zip` - it is a Liberty server zip.

### Option B: Download a Server Build

To run tests against an existing deployment, you can download a pre-built server.

- Server Builds are actually attached to each action run in <https://github.com/unicode-org/cldr/actions/workflows/maven.yml>, look for an artifact entitled `cldr-apps-server` at the bottom of a run.

- *Warning*: Clicking on this artifact will download a zipfile named `cldr-apps-server.zip` which _contains_ `cldr-apps.zip`.  Double clicking or automatic downloading will often extract one too many levels of zipfiles. If you see a folder named `wlp` then you have extracted too much. From the command line you can unpack with `unzip cldr-apps-server.zip` which will extract `cldr-apps.zip`.  Unpack the file to `tools/cldr-apps/target/cldr-apps.zip`

### Build and run

```shell
cd tools/cldr-apps
docker compose up cldr-apps
```

### webdriver

This will compile and launch the [webdriver](../cldr-apps-webdriver/README.md).

```shell
cd tools/cldr-apps
docker compose run --rm -it webdriver
```

#### VNC into the WebDriver

Want to see what the WebDriver is doing? Sure, you can.

1. Start the selenium container with `docker compose up -d selenium`
2. Get the local VNC port number: use `docker ps` and it will show up, for example look for `0.0.0.0:39999->5900/tcp` meaning that 39999 is the randomly assigned port (5900 is the VNC port number, but we don't assign that locally.). Or, get the local noVNC port number, which will be  mapped to 7900, `0.0.0.0:37777->7900/tcp` indicating port 37777
3. For noVNC, just point a browser at <http://localhost:37777> (in the above example). In Docker Desktop, you might be able to _click_ on the port number mapped to 7900 to launch a browser.
   For VNC, use a VNC client to connect to `localhost:39999` (for example).  <vnc://localhost:39999> might work in a browser.
4. Enter the "secret" password, more details [here](https://github.com/SeleniumHQ/docker-selenium?tab=readme-ov-file#using-a-vnc-client).
5. Now run the webdriver (above) and you can watch the brower go.

### client tests

This will launch the [client tests](js/test/client/README.md).

```shell
cd tools/cldr-apps
docker compose run --rm -it client-test
```

### Licenses

See the main [README.md](../../README.md).

### Copyright

Copyright © 2004-2025 Unicode, Inc. Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in the United States and other countries.
