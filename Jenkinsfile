pipeline {
    agent any
    tools {
        jdk 'JDK24'
        maven 'Maven3'
    }
    environment {
        SONAR_TOKEN = credentials('SONAR_TOKEN')
        GITHUB_TOKEN = credentials('GITHUB_TOKEN')
        SONAR_HOST_URL = 'http://sonarqube:9000' // Update to 'https://60b80221b360.ngrok-free.app' if needed
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
                        ls -l $JAVA_HOME/bin
                        java -version
                        mvn -version
                        mvn -B verify org.sonarsource.scanner.maven:sonar-maven-plugin:5.1.0.4751:sonar \
                          -Dsonar.projectKey=japheth-waswa_DB-Backup_AZgt3hfv_mqXGzXgC5qx \
                          -Dsonar.host.url=$SONAR_HOST_URL \
                          -Dsonar.login=$SONAR_TOKEN \
                          -Dsonar.java.source=24 \
                          -Dsonar.github.repository=japheth-waswa/DB-Backup \
                          -Dsonar.github.oauth=$GITHUB_TOKEN \
                          -Dmaven.compiler.release=24
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