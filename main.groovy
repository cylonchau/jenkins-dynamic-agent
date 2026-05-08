@Library('dynamic-agent@20251024') _

import groovy.json.JsonOutput
import io.jenkins.agentmanager.AgentManager
import io.jenkins.common.Init
import io.jenkins.build.ImageMaker
import io.jenkins.build.Compilation
import io.jenkins.deploy.Deployment

pipeline {
  agent any
  options {
    ansiColor('xterm')
    buildDiscarder(logRotator(numToKeepStr: '10'))
  }
  stages {
    stage('载入配置') {
      steps {
        script {
          this.init             = new Init(this)
          this.image_builer     = new ImageMaker(this)
          this.build_client     = new Compilation(this)
          this.deploy_client    = new Deployment(this)
          this.agent_mgr        = AgentManager.init(this)
          this.hook_funcs       = load 'utils/hook.groovy'
          init.initGlobalVariables()
          if (env.DOWNLOAD_FROM_RELEASE == 'true') {
            env.CURRENT_COMMIT_ID = "release-download"
          }
        }
      }
    }

    stage('拉取代码') {
      when {
        expression { !common.shouldSkipStage("checkout") }
      }
      steps {
        script {
          def appModule = readJSON text: env.APP_MODULE
          def moduleList = (params.MODULES ?: env.MODULES ?: '').split(',').collect { it.trim() }.findAll { it }
          // 检查是否存在带 git 的富模块配置
          def richModules = moduleList.findAll { mod -> appModule[mod] instanceof Map && appModule[mod].git }

          if (richModules) {
            // 1. 【聚合模式 / 多仓库模式】
            script.echo "🏗️ 检测到多仓库聚合模式，开始按模块拉取代码..."
            richModules.each { mod ->
              def config = appModule[mod]
              dir("${env.ROOT_WORKSPACE}/${mod}-src") {
                def branchToPull = params["BRANCH_${mod}"] ?: config.branch ?: 'master'
                def cid = git_client.pullCode(config.git, env.GIT_CREDNTIAL, branchToPull)
                // 使用第一个模块的 commitId 作为基础镜像标识
                if (!env.CURRENT_COMMIT_ID) env.CURRENT_COMMIT_ID = cid
              }
            }
          } else if (env.GIT_REPO && env.GIT_REPO != "null" && env.GIT_REPO != "") {
            // 2. 【传统模式 / 单仓库模式】
            def PROJECT_DIR = "${env.ROOT_WORKSPACE}/${env.MAIN_PROJECT}"
            dir("${PROJECT_DIR}") {
              env.CURRENT_COMMIT_ID = git_client.pullCode(env.GIT_REPO, env.GIT_CREDNTIAL, params.selectedBranch, params.FORCE_COMMIT ?: "")
            }
          }

          // 3. 统一 Stash (在根目录执行，确保能抓取到所有子目录)
          if (env.BUILD_PLATFORM == "kubernetes" && !common.shouldSkipStage("compile")) { 
            stash(
              name: 'build-dir',
              includes: '**',
              excludes: '.git/**, .docker/**, .gitignore, settings.xml',
              allowEmpty: true
            )
          }
        }
      }
    }

    stage('编译') {
      when {
        expression { !common.shouldSkipStage("compile") }
      }
      steps {
        script {
          def agent_type = env.BUILD_PLATFORM ? env.BUILD_PLATFORM : (env.PLATFORM ? env.PLATFORM : 'any')
          try {
            agent_mgr.build(agent_type)
          } catch (Exception e) {
            echo "❌ 构建失败: ${e}"
            e.printStackTrace()
            error("构建阶段失败，停止执行")
          }
        }
      }
    }

    stage('发布') {
      when {
        expression { !common.shouldSkipStage("deploy") }
      }
      steps {
        script {
          try {
            deploy_client.mainDeployStage()
          } catch (Exception e) {
            echo "❌ 发布失败: ${e}"
            e.printStackTrace()
            error("发布阶段失败，停止执行")
          }
        }
      }
    }
  }
  post {
    always {
      script {
        def buildResult = currentBuild.result ?: 'SUCCESS'
        def commitId = env.CURRENT_COMMIT_ID?.trim() ?: env.PREVIOUS_COMMIT_ID?.trim()
        def descMap = [
          commit       : commitId ?: "N/A",
          success      : env.PREVIOUS_BUILD_SUCCESS == 'true',
          modules      : (params.MODULES ?: "").split(',')
                            .collect { it.trim() }
                            .findAll { it }
                            .join(','),
          imageUploaded: (env.IMAGE_UPLOAD_SUCCESS ?: env.PREVIOUS_IMAGE_UPLOADED) == 'true',
          exec         : buildResult == 'SUCCESS'
        ]

        currentBuild.description = groovy.json.JsonOutput.toJson(descMap)
      }
    }
  }
}