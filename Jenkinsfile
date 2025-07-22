pipeline {
    agent any

    tools {
        jdk 'JDK24'
        maven 'Maven3'
    }

    environment {
        JAVA_HOME = "${tool 'JDK24'}"
        PATH = "${tool 'JDK24'}/bin:${env.PATH}"
        SONAR_TOKEN = credentials('SONAR_TOKEN')
        GITHUB_TOKEN = credentials('GITHUB_TOKEN')
    }

    stages {
        stage('Checkout') {
            steps {
                git url: 'https://github.com/japheth-waswa/DB-Backup.git',
                    credentialsId: 'GITHUB_TOKEN',
                    branch: 'main'
            }
        }

        stage('Build and Analyze') {
            steps {
                withSonarQubeEnv('SonarQube') {
                    sh '''
                        echo "JAVA_HOME=$JAVA_HOME"
                        java -version
                        mvn -B verify org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
                          -Dsonar.projectKey=japheth-waswa_DB-Backup_AZgt3hfv_mqXGzXgC5qx \
                          -Dsonar.java.source=24 \
                          -Dsonar.github.repository=japheth-waswa/DB-Backup \
                          -Dsonar.github.oauth=$GITHUB_TOKEN
                    '''
                }
            }
        }

        stage('Quality Gate') {
            steps {
                timeout(time: 5, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }
    }
}
