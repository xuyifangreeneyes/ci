if (params.containsKey("release_test") && params.get("release_test") == "true") {
    echo "this build is triggered by qa for release testing"
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__tiflash_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

def runTest(label, name, path, tidb_branch) {
    podTemplate(
        name: label, 
        label: label,
        instanceCap: 15,
        containers: [
            containerTemplate(name: 'dockerd', image: 'docker:18.09.6-dind', privileged: true,
                    resourceRequestCpu: '5000m', resourceRequestMemory: '10Gi',
                    resourceLimitCpu: '16000m', resourceLimitMemory: '32Gi'),
            containerTemplate(name: 'docker', image: 'hub.pingcap.net/jenkins/docker:build-essential-java',
                    alwaysPullImage: true, envVars: [envVar(key: 'DOCKER_HOST', value: 'tcp://localhost:2375')], ttyEnabled: true, command: 'cat')],
        volumes: [
            nfsVolume(mountPath: '/home/jenkins/agent/dependency', serverAddress: '172.16.5.22',
                    serverPath: '/mnt/ci.pingcap.net-nfs/tiflash/dependency', readOnly: true),
            nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                    serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: true)]) 
    {
        node(label) {
            stage('Unstash') {
                unstash 'code-and-artifacts'
            }
            stage('Run') {
                dir(path) {
                    timeout(time: 60, unit: 'MINUTES') {
                        container("docker") {
                            try {
                                echo "path: ${pwd()}"
                                sh "TAG=${ghprbActualCommit} BRANCH=${tidb_branch} bash -xe ./run.sh"
                            } catch (e) {
                                sh "mv log ${name}-log"
                                archiveArtifacts(artifacts: "${name}-log/**/*.log", allowEmptyArchive: true)
                                sh "find ${name}-log -name '*.log' | xargs tail -n 500"
                                sh "docker ps -a"
                                throw e
                            }
                        }
                    }
                }
            }
        }
    }
}

def checkoutTiFlash() {
    container('golang') {
        stage('Checkout') {
            def cache_path = "/home/jenkins/agent/ci-cached-code-daily/src-tics.tar.gz"
            if (fileExists(cache_path)) {
                println "get code from nfs to reduce clone time"
                sh """
                set +x
                cp -R ${cache_path}  ./
                tar -xzf ${cache_path} --strip-components=1
                rm -f src-tics.tar.gz
                chown -R 1000:1000 ./
                set -x
                """
            }
            
            def refspec = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
            
            if (!ghprbPullId) {
                refspec = "+refs/heads/*:refs/remotes/origin/*"
            }
            
            checkout(changelog: false, poll: false, 
                scm: [
                    $class                           : "GitSCM",
                    branches                         : [[name: ghprbActualCommit]],
                userRemoteConfigs : [
                    [
                        url           : "git@github.com:pingcap/tiflash.git",
                        refspec       : refspec,
                        credentialsId : "github-sre-bot-ssh",
                    ]
                ],
                extensions : [
                    [$class             : 'SubmoduleOption',
                    disableSubmodules   : true,
                    parentCredentials   : true,
                    recursiveSubmodules : true,
                    trackingSubmodules  : false,
                    reference           : ''],
                    [$class: 'PruneStaleBranch'],
                            [$class: 'CleanBeforeCheckout'],
                    ],
                    doGenerateSubmoduleConfigurations: false,
                ])
        }
        dir('tests/.build') {
            stage('Get Artifacts') {
                copyArtifacts(
                    projectName: 'tiflash-build-common',
                    parameters: "TARGET_BRANCH=${ghprbTargetBranch},TARGET_PULL_REQUEST=${ghprbPullId},TARGET_COMMIT_HASH=${ghprbActualCommit},BUILD_TIFLASH=true",
                    selector: lastSuccessful(),
                    optional: false)
                sh "tar -zxvf tiflash.tar.gz"
            }
        }

        stage('Stash') {
            sh """
            rm -rf .git
            rm -rf contrib
            """
            stash name: "code-and-artifacts", useDefaultExcludes: true
        }
    }
}

node(GO_TEST_SLAVE) {
    def toolchain = null
    def identifier = "tiflash-integration-test-${ghprbTargetBranch}-${ghprbPullId}"
    def repo_path = "/home/jenkins/agent/workspace/tiflash-build-common/tiflash"
    def tidb_branch = ({
        def m = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
        if (m) {
            return "${m.group(1)}"
        }
        return ghprbTargetBranch ?: 'master'
    }).call()
    if (ghprbTargetBranch in ["planner_refactory"]) {
        tidb_branch = "master"
    }

    stage('Wait for Build') {
        def api = "https://ci.pingcap.net/job/tiflash-build-common/api/xml?tree=allBuilds[result,building,actions[parameters[name,value]]]&xpath=(//allBuild[action[parameter[name=%22TARGET_COMMIT_HASH%22%20and%20value=%22${ghprbActualCommit}%22]%20and%20parameter[name=%22BUILD_TIFLASH%22%20and%20value=%22true%22]]])[1]"
        echo "poll status from: ${api}"
        waitUntil(quiet: true) {
            def response = httpRequest(url: api, validResponseCodes: "100:399,404", quiet: true)
            def content = response.getContent()
            if (content.contains('<result>FAILURE</result>')) {
                error "build failure"
            }
            if (!content.contains('<building>false</building>') || !content.contains('<result>SUCCESS</result>')) {
                return false
            }
            return true
        }
        copyArtifacts(
            projectName: 'tiflash-build-common',
            parameters: "TARGET_BRANCH=${ghprbTargetBranch},TARGET_PULL_REQUEST=${ghprbPullId},TARGET_COMMIT_HASH=${ghprbActualCommit},BUILD_TIFLASH=true",
            filter: 'toolchain',
            selector: lastSuccessful(),
            optional: false
        )
        toolchain = readFile(file: 'toolchain').trim()
        echo "Built with ${toolchain}"
    }

    checkoutTiFlash()

    parallel (
        "tidb ci test": {
            def name = "tidb-ci-test"
            runTest("${identifier}-${name}-${BUILD_NUMBER}", name, "tests/tidb-ci", tidb_branch)
        },
        "delta merge test": {
            def name = "delta-merge-test"
            runTest("${identifier}-${name}-${BUILD_NUMBER}", name, "tests/delta-merge-test", tidb_branch)
        },
        "fullstack test": {
            def name = "fullstack-test"
            runTest("${identifier}-${name}-${BUILD_NUMBER}", name, "tests/fullstack-test", tidb_branch)
        },
        "fullstack test2": {
            def name = "fullstack-test2"
            runTest("${identifier}-${name}-${BUILD_NUMBER}", name, "tests/fullstack-test2", tidb_branch)
        }
    )
}

stage("Sync Status") {
    node {
        sh """curl --connect-timeout 2 --max-time 4 -d '{"job":"$JOB_NAME","id":$BUILD_NUMBER}' http://172.16.5.13:36000/api/v1/ci/job/sync || true"""
    }
}
