package com.sourcemuse.gradle.plugin

import com.mongodb.MongoCredential
import de.flapdoodle.embed.mongo.distribution.Version
import org.bson.Document
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.littleshoot.proxy.impl.DefaultHttpProxyServer
import spock.lang.Issue
import spock.lang.Specification

import static com.sourcemuse.gradle.plugin.BuildScriptBuilder.*
import static com.sourcemuse.gradle.plugin.MongoUtils.*

class MongoPluginConfigSpec extends Specification {

    def static final VERBOSE_LOGGING_SAMPLE = 'isMaster'

    @Rule
    TemporaryFolder tmp
    def buildScript = new BuildScriptBuilder()

    def 'port is configurable'() {
        given:
        generate(buildScript.withPort(12345))
        def args = START_MONGO_DB_FOR_TEST

        when:
        runGradle(args)
        def mongoRunningOnPort = mongoInstanceRunning(12345)

        then:
        mongoRunningOnPort
    }

    def 'logging can route to the console'() {
        given:
        generate(buildScript.withLogging('console'))
        def args = START_MONGO_DB_FOR_TEST

        when:
        def executionResult = runGradle(args)

        then:
        executionResult.getOutput().contains('[mongod output]')
    }

    def 'logging can be switched off'() {
        given:
        generate(buildScript.withLogging('none'))
        def args = START_MONGO_DB_FOR_TEST

        when:
        def executionResult = runGradle(args)

        then:
        !executionResult.getOutput().contains('[mongod output]')
    }

    def 'logging can be routed to a file'() {
        given:
        def tempFile = tmp.newFile()
        generate(buildScript.withLogging('file').withFilePath(tempFile.absolutePath))
        def args = START_MONGO_DB_FOR_TEST

        when:
        def executionResult = runGradle(args)

        then:
        !executionResult.getOutput().contains('[mongod output]')
        tempFile.text.contains('[mongod output]')
    }

    def 'general version is configurable'() {
        given:
        generate(buildScript.withMongoVersion('PRODUCTION'))
        def args = START_MONGO_DB_FOR_TEST

        when:
        runGradle(args)
        def mongoVersion = getMongoVersionRunning(DEFAULT_MONGOD_PORT)

        then:
        Version.Main.PRODUCTION.asInDownloadPath().equalsIgnoreCase(mongoVersion)
    }

    @SuppressWarnings('GrDeprecatedAPIUsage')
    def 'specific version is configurable'() {
        given:
        generate(buildScript.withMongoVersion('2.5.4'))
        def args = START_MONGO_DB_FOR_TEST

        when:
        runGradle(args)
        def mongoVersion = getMongoVersionRunning(DEFAULT_MONGOD_PORT)

        then:
        Version.V2_5_4.asInDownloadPath().equalsIgnoreCase(mongoVersion)
    }

    def 'latest branch version is configurable'() {
        given:
        generate(buildScript.withMongoVersion('3.5-LATEST'))
        def args = START_MONGO_DB_FOR_TEST

        when:
        runGradle(args)
        def mongoVersion = getMongoVersionRunning(DEFAULT_MONGOD_PORT)

        then:
        Version.Main.V3_5.asInDownloadPath().equalsIgnoreCase(mongoVersion)
    }

    @Issue('https://github.com/sourcemuse/GradleMongoPlugin/issues/15')
    def 'unrecognized version is configurable'() {
        given:
        def version = '3.2.0'
        generate(buildScript.withMongoVersion(version))
        def args = START_MONGO_DB_FOR_TEST

        when:
        runGradle(args)
        def mongoVersion = getMongoVersionRunning(DEFAULT_MONGOD_PORT)

        then:
        mongoVersion == version
    }

    def 'storage engine can be set to WiredTiger'() {
        given:
        generate(buildScript.withMongoVersion(Version.Main.V3_2.asInDownloadPath()).withStorageEngine('wiredTiger'))
        def args = START_MONGO_DB_FOR_TEST

        when:
        runGradle(args)

        then:
        mongoServerStatus().storageEngine.name == 'wiredTiger'
        noExceptionThrown()
    }

    def 'storage engine can be set to MMAPv1'() {
        given:
        generate(buildScript.withMongoVersion(Version.Main.V3_2.asInDownloadPath()).withStorageEngine('mmapv1'))
        def args = START_MONGO_DB_FOR_TEST

        when:
        runGradle(args)

        then:
        mongoServerStatus().storageEngine.name == 'mmapv1'
        noExceptionThrown()
    }

    def 'the default storage engine is WiredTiger for versions after 3.2'() {
        given:
        generate(buildScript.withMongoVersion(Version.Main.V3_2.asInDownloadPath()))
        def args = START_MONGO_DB_FOR_TEST

        when:
        runGradle(args)

        then:
        mongoServerStatus().storageEngine.name == 'wiredTiger'
        noExceptionThrown()
    }

    def 'the default storage engine is MMAPv1 for versions before 3.0'() {
        given:
        generate(buildScript.withMongoVersion(Version.Main.V3_0.asInDownloadPath()))
        def args = START_MONGO_DB_FOR_TEST

        when:
        runGradle(args)

        then:
        mongoServerStatus().storageEngine.name == 'mmapv1'
        noExceptionThrown()
    }

    def 'replication storage location is configurable'() {
        given:
        def storageDir = tmp.newFolder()
        generate(buildScript.withStorageLocation(storageDir.toString()))
        def args = START_MONGO_DB_FOR_TEST

        when:
        runGradle(args)

        then:
        storageDir.listFiles().size() > 0
    }

    def 'journaling can be enabled'() {
        given:
        // From 2.6 onwards, journaled writes onto a non-journaled mongo db throw exceptions
        generate(buildScript.withJournalingEnabled().withMongoVersion('2.6.1'))
        def args = START_MONGO_DB_FOR_TEST

        when:
        runGradle(args)
        makeJournaledWrite()

        then:
        noExceptionThrown()
    }

    def 'logging can be made verbose'() {
        given:
        generate(buildScript.withVerboseLogging().withLogging('console'))
        def args = START_MONGO_DB_FOR_TEST

        when:
        def executionResult = runGradle(args)

        then:
        executionResult.getOutput().contains(VERBOSE_LOGGING_SAMPLE)
        println executionResult.getOutput()
    }

    def 'by default logging is not verbose'() {
        given:
        generate(buildScript.withLogging('console'))
        def args = START_MONGO_DB_FOR_TEST

        when:
        def executionResult = runGradle(args)

        then:
        !executionResult.getOutput().contains(VERBOSE_LOGGING_SAMPLE)
    }

    def 'a URL that does not resolve to a mongo binary will fail'() {
        given:
        generate(buildScript.withDownloadURL('http://www.google.com').withMongoVersion('1.6.5'))
        def args = START_MONGO_DB_FOR_TEST

        when:
        GradleRunner.create()
            .withPluginClasspath()
            .withProjectDir(tmp.root)
            .withArguments(args)
            .buildAndFail()

        then:
        noExceptionThrown()
    }

    def 'will fail with non-routeable proxy'() {
        given:
        int proxyPort = 9091
        String proxyHost = 'invalidHost'
        String path = tmp.newFolder().toString()
        generate(buildScript.withProxy(proxyHost, proxyPort).withArtifactStorePath(path))
        def args = START_MONGO_DB_FOR_TEST

        when:
        def executionResult = GradleRunner.create()
            .withPluginClasspath()
            .withProjectDir(tmp.root)
            .withArguments(args)
            .buildAndFail()

        then:
        noExceptionThrown()
    }

    def 'can use proxy to download and a custom location'() {
        given:
        int proxyPort = 9091
        DefaultHttpProxyServer.bootstrap().withPort(proxyPort).start()
        String path = tmp.newFolder().toString()
        generate(buildScript.withProxy('localhost', proxyPort).withArtifactStorePath(path))
        def args = START_MONGO_DB_FOR_TEST

        when:
        runGradle(args)

        then:
        mongoInstanceRunning()
        noExceptionThrown()
    }

    def 'can start/stop with authentication enabled'() {
        given:
        generate(buildScript.withAuth())
        def args = START_MANAGED_MONGO_DB_FOR_TEST

        when:
        def result = runGradle(args)
        def mongoRunningDuringBuild = result.getOutput().contains(MONGO_RUNNING_FLAG)
        def mongoRunningAfterBuild = mongoInstanceRunning()

        then:
        mongoRunningDuringBuild
        !mongoRunningAfterBuild
    }

    def 'unauthenticated commands are rejected'() {
        given:
        generate(buildScript.withAuth().withMongoVersion("3.4.0"))
        def cred = MongoCredential.createCredential('admin', 'admin', 'qwert123'.toCharArray())

        when:
        runGradle(START_MONGO_DB_FOR_TEST)
        def cmd = new Document('createUser', 'admin')
        cmd.put('pwd', 'qwert123')
        cmd.put('roles', ['root'])
        def unauthSuccess = runMongoCommand(null, cmd)

        then:
        mongoInstanceRunning()
        unauthSuccess

        when:
        def authSuccess = runMongoCommand(cred, new Document('dbStats', 1))
        unauthSuccess = runMongoCommand(null, new Document('dbStats', 1))

        then:
        authSuccess
        !unauthSuccess

        when:
        // Mongod.sendShutdown will not work when authentication is enabled, so perform a special cleanup
        runMongoCommand(cred, new Document('shutdown', 1))

        then:
        noExceptionThrown()
        !mongoInstanceRunning()
    }

    def 'parameters can be set'() {
        given:
        generate(buildScript.withParams([cursorTimeoutMillis: '300000']))
        def args = START_MONGO_DB_FOR_TEST

        when:
        runGradle(args)

        then:
        noExceptionThrown()
    }

    def 'custom command line arguments can be set'() {
        given:
        generate(buildScript.withArgs([logappend: '', maxConns: '1000']))
        def args = START_MONGO_DB_FOR_TEST

        when:
        runGradle(args)

        then:
        noExceptionThrown()
    }

    def cleanup() {
        ensureMongoIsStopped(buildScript.configuredPort ?: DEFAULT_MONGOD_PORT)
    }

    BuildResult runGradle(String args) {
        return GradleRunner.create()
            .withPluginClasspath()
            .withProjectDir(tmp.root)
            .withArguments(args)
            .build()
    }

    void generate(BuildScriptBuilder buildScriptBuilder) {
        def buildScriptContent = buildScriptBuilder.build()
        tmp.newFile('build.gradle') << buildScriptContent
    }
}
