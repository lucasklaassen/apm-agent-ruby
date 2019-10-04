#!/usr/bin/env groovy
@Library('apm@current') _

import co.elastic.matrix.*
import groovy.transform.Field

/**
This is the parallel tasks generator,
it is need as field to store the results of the tests.
*/
@Field def rubyTasksGen

pipeline {
  agent { label 'linux && immutable' }
  environment {
    REPO="git@github.com:elastic/apm-agent-ruby.git"
    BASE_DIR="src/github.com/elastic/apm-agent-ruby"
    PIPELINE_LOG_LEVEL='INFO'
    NOTIFY_TO = credentials('notify-to')
    JOB_GCS_BUCKET = credentials('gcs-bucket')
    JOB_GIT_CREDENTIALS = "f6c7695a-671e-4f4f-a331-acdce44ff9ba"
    DOCKER_REGISTRY = 'docker.elastic.co'
    DOCKER_SECRET = 'secret/apm-team/ci/docker-registry/prod'
    CODECOV_SECRET = 'secret/apm-team/ci/apm-agent-ruby-codecov'
  }
  options {
    timeout(time: 2, unit: 'HOURS')
    buildDiscarder(logRotator(numToKeepStr: '300', artifactNumToKeepStr: '20', daysToKeepStr: '30'))
    timestamps()
    ansiColor('xterm')
    disableResume()
    durabilityHint('PERFORMANCE_OPTIMIZED')
    rateLimitBuilds(throttle: [count: 60, durationName: 'hour', userBoost: true])
    quietPeriod(10)
  }
  parameters {
    string(name: 'RUBY_VERSION', defaultValue: "ruby:2.6", description: "Ruby version to test")
    string(name: 'BRANCH_SPECIFIER', defaultValue: "master", description: "Git branch/tag to use")
  }
  stages {
    /**
    Checkout the code and stash it, to use it on other stages.
    */
    stage('Checkout') {
      options { skipDefaultCheckout() }
      steps {
        deleteDir()
        gitCheckout(basedir: "${BASE_DIR}",
          branch: "${params.BRANCH_SPECIFIER}",
          repo: "${REPO}",
          credentialsId: "${JOB_GIT_CREDENTIALS}")
        stash allowEmpty: true, name: 'source', useDefaultExcludes: false
      }
    }
    stage('Test') {
      options { skipDefaultCheckout() }
      steps {
        runTests('.ci/.jenkins_framework.yml')
      }
    }
    stage('Master Test') {
      options { skipDefaultCheckout() }
      steps {
        catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE', message: "The tests for the master framework have failed. Let's warn instead.") {
          runTests('.ci/.jenkins_master_framework.yml')
        }
      }
    }
  }
  post {
    cleanup {
      script{
        if(rubyTasksGen?.results){
          writeJSON(file: 'results.json', json: toJSON(rubyTasksGen.results), pretty: 2)
          def mapResults = ["Ruby": rubyTasksGen.results]
          def processor = new ResultsProcessor()
          processor.processResults(mapResults)
          archiveArtifacts allowEmptyArchive: true, artifacts: 'results.json,results.html', defaultExcludes: false
          catchError(buildResult: 'SUCCESS') {
            def datafile = readFile(file: "results.json")
            def json = getVaultSecret(secret: 'secret/apm-team/ci/jenkins-stats-cloud')
            sendDataToElasticsearch(es: json.data.url, data: datafile, restCall: '/jenkins-builds-ruby-test-results/_doc/')
          }
        }
      }
      notifyBuildResult()
    }
  }
}

/**
Parallel task generator for the integration tests.
*/
class RubyParallelTaskGenerator extends DefaultParallelTaskGenerator {

  public RubyParallelTaskGenerator(Map params){
    super(params)
  }

  /**
  build a clousure that launch and agent and execute the corresponding test script,
  then store the results.
  */
  public Closure generateStep(x, y){
    return {
      steps.sleep steps.randomNumber(min:10, max: 30)
      steps.node('linux && immutable'){
        // Label is transformed to avoid using the internal docker registry in the x coordinate
        // TODO: def label = "${tag}:${x?.drop(x?.lastIndexOf('/')+1)}#${y}"
        def label = "${tag}:${x}#${y}"
        try {
          steps.runScript(label: label, ruby: x, framework: y)
          saveResult(x, y, 1)
        } catch(e){
          saveResult(x, y, 0)
          steps.error("${label} tests failed : ${e.toString()}\n")
        } finally {
          steps.junit(allowEmptyResults: true,
            keepLongStdio: true,
            testResults: "**/spec/ruby-agent-junit.xml")
          if (steps.isCodecovEnabled(x, y)) {
            steps.codecov(repo: "${steps.env.REPO}", basedir: "${steps.env.BASE_DIR}",
                          secret: "${steps.env.CODECOV_SECRET}")
          }
        }
      }
    }
  }
}

/**
  Run tests for a Ruby version and framework version.
*/
def runScript(Map params = [:]){
  def label = params.label
  def ruby = params.ruby
  def framework = params.framework
  log(level: 'INFO', text: "${label}")
  env.HOME = "${env.WORKSPACE}"
  env.PATH = "${env.PATH}:${env.WORKSPACE}/bin"
  deleteDir()
  unstash 'source'
  dir("${BASE_DIR}"){
    retry(2){
      sleep randomNumber(min:10, max: 30)
      dockerLogin(secret: "${DOCKER_SECRET}", registry: "${DOCKER_REGISTRY}")
      sh("./spec/scripts/spec.sh ${ruby} ${framework}")
    }
  }
}

/**
* Whether the given ruby version and framework are in charge of sending the
* codecov results. It does require the workspace.
*/
def isCodecovEnabled(ruby, framework){
  dir(BASE_DIR){
    def codecovVersions = readYaml(file: '.ci/.jenkins_codecov.yml')
    return codecovVersions['ENABLED'].any { it.trim() == "${ruby?.trim()}#${framework?.trim()}" }
  }
}

/**
  Run all the tests for the given ruby version and file with the frameworks to test
*/
def runTests(frameworkFile) {
  deleteDir()
  unstash "source"
  dir("${BASE_DIR}"){
    script {
      rubyTasksGen = new RubyParallelTaskGenerator(
        xVersions: [ "${params.RUBY_VERSION}" ],
        xKey: 'RUBY_VERSION',
        yKey: 'FRAMEWORK',
        yFile: frameworkFile,
        exclusionFile: '.ci/.jenkins_exclude.yml',
        tag: 'Ruby',
        name: 'Ruby',
        steps: this
      )
      def mapPatallelTasks = rubyTasksGen.generateParallelTests()
      parallel(mapPatallelTasks)
    }
  }
}
