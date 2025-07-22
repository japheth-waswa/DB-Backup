pipeline {
    agent any
    tools {
        jdk 'JDK24'
        maven 'Maven3'
    }
    environment {
        SONAR_TOKEN = credentials('SONAR_TOKEN')
        SONAR_HOST_URL = 'http://sonarqube:9000'
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
                    withEnv(["JAVA_HOME=${tool 'JDK24'}", "PATH+JAVA=${tool 'JDK24'}/bin"]) {
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
