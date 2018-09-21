pipeline {
  agent {
    docker {
      image 'openjdk:8-jdk-slim'
      args '-v gradle-cache:/root/.gradle:rw'
    }

  }
  stages {
    stage('Build') {
      steps {
        sh './gradlew build --no-daemon'
        archiveArtifacts 'build/libs/bungeequack*'
      }
    }
  }
}