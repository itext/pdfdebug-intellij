#!/usr/bin/env groovy
@Library('pipeline-library')_
import com.itextpdf.ColorCodings

def schedule = env.BRANCH_NAME.contains('master') ? '@monthly' : env.BRANCH_NAME == 'develop' ? '@midnight' : ''

pipeline {

    agent any

    environment {
        JDK_VERSION = 'jdk-8-oracle'
    }

    options {
        ansiColor('xterm')
        buildDiscarder(logRotator(artifactNumToKeepStr: '1'))
        parallelsAlwaysFailFast()
        retry(1)
        skipStagesAfterUnstable()
        timeout(time: 60, unit: 'MINUTES')
        timestamps()
    }

    triggers {
        cron(schedule)
    }

    tools {
        jdk "${JDK_VERSION}"
    }

    stages {
        stage('Clean workspace') {
            options {
                timeout(time: 5, unit: 'MINUTES')
            }
            steps {
                cleanWs deleteDirs: true, patterns: [
                    [pattern: 'build', type: 'INCLUDE']
                ]
            }
        }
        stage('Build') {
            options {
                timeout(time: 5, unit: 'MINUTES')
            }
            steps {
                sh './gradlew buildPlugin'
            }
        }
        stage('Artifactory Deploy') {
            options {
                timeout(time: 5, unit: 'MINUTES')
            }
            when {
                anyOf {
                    branch "master"
                    branch "develop"
                }
            }
            steps {
                script {
                    getAndConfigureJFrogCLI()
                    findFiles(glob: 'build/distributions/*.zip').each { item ->
                        upload(item)
                    }
                }
            }
        }
        stage('Archive Artifacts') {
            options {
                timeout(time: 5, unit: 'MINUTES')
            }
            steps {
                archiveArtifacts allowEmptyArchive: true, artifacts: 'build/distributions/*.zip'
            }
        }
    }

    post {
        always {
            echo 'One way or another, I have finished \uD83E\uDD16'
        }
        success {
            echo 'I succeeeded! \u263A'
        }
        unstable {
            echo 'I am unstable \uD83D\uDE2E'
        }
        failure {
            echo 'I failed \uD83D\uDCA9'
        }
        changed {
            echo 'Things were different before... \uD83E\uDD14'
        }
        fixed {
            script {
                if ((env.BRANCH_NAME == 'master') || (env.BRANCH_NAME == 'develop')) {
                    slackNotifier("#ci", currentBuild.currentResult, "${env.BRANCH_NAME} - Back to normal")
                }
            }
        }
        regression {
            script {
                if ((env.BRANCH_NAME == 'master') || (env.BRANCH_NAME == 'develop')) {
                    slackNotifier("#ci", currentBuild.currentResult, "${env.BRANCH_NAME} - First failure")
                }
            }
        }
    }
}

@NonCPS
def upload(item) {
    println "item: ${item}"
    def itemArray = (item =~ /.*?(pdfdebug-intellij-[0-9]*\.[0-9]*\.[0-9]*(-SNAPSHOT)?\.zip)/)
    def version = itemArray[ 0 ][ 1 ]
    println "./jfrog rt upload ${item} files/com/itextpdf/pdfdebug-intellij/${version}/${item} --flat=true --build-name=${env.JOB_NAME} --build-number=${env.BUILD_NUMBER}"
    println "./jfrog rt bp ${env.JOB_NAME} ${env.BUILD_NUMBER} --build-url ${env.BUILD_URL}"
}
