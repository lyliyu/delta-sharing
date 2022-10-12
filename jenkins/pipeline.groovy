#!groovy

@Library("microservice") _
//library 'elis-gss-jenkins-library'

node {
   
    def HELM_VERSION="3.6.3"
    BASE_IMAGES_REGISTRY='nexus-nonprod-gss.uscis.dhs.gov:8124'
	DBIS_IMAGES_REGISTRY='nexus-nonprod-gss.uscis.dhs.gov:8144'

	DATE = sh(script: "echo `date +%F-%T`", returnStdout: true).trim()
	def IMAGE_NAME = "delta.io/delta-sharing-server";
	def MAJOR_VERSION = "0.0.0"

	stage('Checkout code') {
		
        println "==* Checking Out Codebase *=="
        SCM_VARS = checkout scm
        BRANCH_NAME = SCM_VARS.GIT_BRANCH.replace("origin/","").toLowerCase()
		MAJOR_VERSION = "${SCM_VARS.GIT_COMMIT[0..7]}"
		println "${MAJOR_VERSION}"
		sh 'pwd && ls -al'

		currentBuild.displayName = "Build: " + MAJOR_VERSION
	}

	stage('Build and push docker image') {
		
			
		def nonProdRegistryToken = openshift.NON_PROD_REGISTRY_TOKEN
		withCredentials([
			[$class: 'UsernamePasswordMultiBinding',
				credentialsId: nonProdRegistryToken,
				passwordVariable: 'NON_PROD_PASSWORD',
				usernameVariable: 'NON_PROD_USER']])
		{
			withDockerContainer('nexus-nonprod-gss.uscis.dhs.gov:8144/eks-pipeline-utils:1.4') {
				
              
                sh("""
                
                rm -rf build/
                sbt compile 
                sbt docker:stage 
                sbt makePom
                cp target/scala-2.12/root_2.12-0.5.0-SNAPSHOT.pom pom.xml

                """)
                
            }

            withCredentials([file(credentialsId: 'eks-nonprod-kube-secret-file', variable: 'KUBE_CONFIG')]) {
                
                sh("""


                #docker tag docker.io/deltaio/delta-sharing-server:0.5.0-SNAPSHOT ${DBIS_IMAGES_REGISTRY}/${IMAGE_NAME}:${MAJOR_VERSION}
                
                docker build -f server/target/docker/stage/Dockerfile -t ${DBIS_IMAGES_REGISTRY}/${IMAGE_NAME}:${MAJOR_VERSION} server/target/docker/stage
                
                docker login -u ${NON_PROD_USER} -p ${NON_PROD_PASSWORD} ${openshift.NON_PROD_REGISTRY}
                docker push ${DBIS_IMAGES_REGISTRY}/${IMAGE_NAME}:${MAJOR_VERSION}

                """)
            }
		}
			
	    
	}

    stage('Nexus IQ') {
       
        script {
            try {
                nexusPolicyEvaluation failBuildOnNetworkError: true, iqApplication: selectedApplication('delta-io-deltasharing'), iqScanPatterns: [[scanPattern: '**/pom.xml']], iqStage: 'build'
            } catch (e) {
                // do not fail for now
                println e
            }
        }
        
    }

	stage('Run Twistlock') {
		println "==* Twistlock Scanning *=="
       
		build job: "DBIS-Non-Prod/twistlock-scan-image" , 
		wait: false,
		parameters: [
			[$class: 'StringParameterValue', name: 'REPO', value: "${DBIS_IMAGES_REGISTRY}/${IMAGE_NAME}"],
			[$class: 'StringParameterValue', name: 'TAG', value: "${MAJOR_VERSION}"]
			
			]     
        
	}

	stage('Trigger Final Container Build') {
		build job: "DBIS-Non-Prod/build-delta-sharing-server-image" , 
		wait: false,
		parameters: [
			[$class: 'StringParameterValue', name: 'DELTA_IO_TAG', value: "${MAJOR_VERSION}"],
		]    
	}

}
