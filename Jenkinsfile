pipeline {
    agent any
    tools {
        jdk 'JDK24'
        maven 'Maven3'
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
                          -Dsonar.java.source=24 \
                          -Dmaven.compiler.release=24
                    '''
                }
            }
        }
        //stage('Quality Gate') {
        //    steps {
        //        timeout(time: 5, unit: 'MINUTES') {
        //            waitForQualityGate abortPipeline: true
        //        }
        //    }
        //}
    }
}