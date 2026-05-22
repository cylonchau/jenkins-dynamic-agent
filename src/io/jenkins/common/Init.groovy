// src/io/jenkins/common/Init.groovy
package io.jenkins.common

import groovy.json.JsonOutput
import io.jenkins.common.Colors
import io.jenkins.common.Stages
import io.jenkins.common.Init
import io.jenkins.common.CommonTools

class Init implements Serializable {
  private transient script

  private Init(script) {
    this.script = script
  }

  static Init getInstance(script) {
    return new Init(script)
  }

  void initGlobalVariables() {
    script.git_client       = new Stages(script)
    script.common           = new CommonTools(script)
    //初始化配置
    def config = script.readJSON(file: 'modules.json')
    // 自动处理文件夹路径，确保匹配到 modules.json 中的 Key
    def jobName = script.env.JOB_BASE_NAME ?: script.env.JOB_NAME.tokenize('/').last()
    script.echo ">>>> [PIPELINE_VERSION: 1.0.7] Initializing job: ${jobName}"
    def selectedModuleConfig = config[jobName]
    if (selectedModuleConfig == null) {
        script.echo "${Colors.RED}❌ 无法在 modules.json 中找到配置: ${jobName}${Colors.RESET}"
        script.error "配置丢失"
    }
    script.selectedModuleConfig = selectedModuleConfig
    def globalConfig = config["global"]

    // job 配置
    script.env.SELECTED_MODULE_CONFIG_JSON      = JsonOutput.toJson(selectedModuleConfig)
    script.env.GLOBAL_CONFIG_JSON               = JsonOutput.toJson(globalConfig)
    script.env.PROGRAMMING                      = selectedModuleConfig.programming?.toString() ?: ""
    script.env.DOCKER_REGISTRY                  = selectedModuleConfig.docker_registry_url_prefix?.toString() ?: ""
    script.env.JOB_PREFIX                       = selectedModuleConfig.job_prefix?.toString() ?: ""
    script.env.JOB_SUFFIX                       = selectedModuleConfig.job_suffix?.toString() ?: ""
    script.env.GIT_REPO                         = selectedModuleConfig.git?.toString() ?: ""
    script.env.BUILD_COMMAND                    = selectedModuleConfig.build_command?.toString() ?: ""
    script.env.BUILD_IMAGE_ARGS                 = selectedModuleConfig.build_image_args?.toString() ?: ""
    script.env.BUILD_PLATFORM                   = selectedModuleConfig.build_platform?.toString() ?: "docker"
    script.env.PLATFORM                         = selectedModuleConfig.platform?.toString() ?: ""
    script.env.LOG_DEBUG                        = selectedModuleConfig.log_debug?.toString() ?: "false"
    script.env.SKIP_BUILD_IMG                   = selectedModuleConfig.skip_build_img?.toString() ?: "false"
    script.env.SKIP_DEPLOY_STAGE                = selectedModuleConfig.skip_deploy_stage?.toString() ?: "false"
    script.env.FORCE_BUILD                      = selectedModuleConfig.force_build?.toString() ?: "false"
    script.env.UPLOAD_FLAG                      = selectedModuleConfig.upload_flag?.toString() ?: "false"
    script.env.NAME_ONLY                        = selectedModuleConfig.name_only?.toString() ?: "false"
    script.env.SHARED_PATH                      = selectedModuleConfig.shared_path?.toString() ?: "false"
    script.env.DOWNLOAD_FROM_RELEASE            = selectedModuleConfig.download_from_release?.toString() ?: "false"
    script.env.ONLY_COMPILE                     = selectedModuleConfig.only_compile?.toString() ?: "false"
    // Kubernetes rollout status 的等待时间。job 级配置优先，其次 global，默认 300s。
    script.env.KUBE_ROLLOUT_TIMEOUT             = selectedModuleConfig.rollout_timeout?.toString() ?: globalConfig.rollout_timeout?.toString() ?: "300s"
    if (selectedModuleConfig.only_compile?.toString() == "true") {
      script.env.SKIP_DEPLOY_STAGE              = (script.params.ONLY_COMPILE != null) ? (script.params.ONLY_COMPILE.toBoolean() ? "true" : "false") : "false"
    }


    if (script.env.BUILD_PLATFORM?.trim() == "kubernetes" || script.env.BUILD_PLATFORM?.trim() == "docker") {
      script.env.IMAGES      = selectedModuleConfig.images?.toString() ?: ""
      script.env.INSIDE_ARGS = selectedModuleConfig.inside_args?.toString() ?: ""
    }

    if (script.env.PLATFORM?.trim() == "vm"){
      script.env.DESTINATION_DIR     = (selectedModuleConfig.destination_dir ?: selectedModuleConfig.destnation_dir)?.toString() ?: ""
      script.env.DESTINATION_HOST    = selectedModuleConfig.destination_host?.toString() ?: ""
      script.env.EXEC_COMMAND        = selectedModuleConfig.exec_command?.toString() ?: ""
      script.env.PRE_EXEC_COMMAND    = selectedModuleConfig.pre_exec_command?.toString() ?: ""
    }
    
    script.env.PRE_BUILD_COMMAND      = selectedModuleConfig.pre_build_command?.toString() ?: ""

    script.env.MAIN_PROJECT           = script.env.GIT_REPO?.trim() ? script.env.GIT_REPO.tokenize('/').last().replaceFirst(/\.git$/, '') : ""
    script.env.ROOT_WORKSPACE         = script.pwd()
    script.env.USED_FALLBACK_BRANCH   = "false"
    script.env.BASE_IMAGE             = selectedModuleConfig.base_image?.toString() ?: ""
    def sharedModuleConfig = selectedModuleConfig.shared_module != null ? selectedModuleConfig.shared_module : selectedModuleConfig.share_module
    def isShared = sharedModuleConfig != null ? sharedModuleConfig.toString().toBoolean() : (selectedModuleConfig.modules instanceof Map && selectedModuleConfig.modules.size() > 1)
    script.env.SHARED_MODULE          = isShared ? "true" : "false"
    script.env.MANIFEST_PREFIX        = selectedModuleConfig.manifest_prefix ?: ""

    // 镜像相关独立变量
    // 解析 docker_registry_url_prefix
    // 例如: "docker.io/nginx"
    def registryPrefix = script.env.DOCKER_REGISTRY
    def parts = registryPrefix.split('/')
    
    script.env.REGISTRY_PROJECT = parts.size() > 1 ? parts[1] : ''  // nginx 对应 hub 的 project

    // 全局配置
    script.env.KUBECONFIG           = globalConfig.kubeconfig?.toString() ?: ""
    script.env.MAVEN_SETTINGS       = globalConfig.maven_settings?.toString() ?: ""
    script.env.MAVEN_CREDENTIAL     = globalConfig.maven_credential?.toString() ?: ''
    script.env.DEFAULT_BRANCH       = globalConfig.default_branch?.toString() ?: ""
    script.env.REGISTRY_CREDENTIAL  = globalConfig.registry_credential?.toString() ?: ""
    script.env.GIT_CREDNTIAL        = globalConfig.git_credential?.toString() ?: ""
    script.env.IMG_REGISTRY         = globalConfig.img_registry?.toString() ?: ""
    if (script.env.IMG_REGISTRY == "" || script.env.IMG_REGISTRY == "https://docker.io") {
        if (parts.size() > 0 && parts[0].contains('.')) {
            script.env.IMG_REGISTRY = "https://${parts[0]}"
        } else {
            script.env.IMG_REGISTRY = "https://docker.io"
        }
    }
    script.env.IMG_REGISTRY_USER    = globalConfig.img_registry_user?.toString() ?: ""
    script.env.IMG_REGISTRY_PWD     = globalConfig.img_registry_pwd?.toString() ?: ""
    
    // 不安全方法
    //env.FALLBACK_BRANCHES = globalConfig.fallback_branches ? globalConfig.fallback_branches.join(',') : "master,test"
    
    // 安全方式
    script.env.FALLBACK_BRANCHES = globalConfig.fallback_branches
        ? globalConfig.fallback_branches.collect { it.toString() }.join(',')
        : "master,test"
    script.env.DEPLOY_CLUSTER = globalConfig.deploy_cluster

    // 初始化菜单
    def paramsList = generateDynamicProperties(selectedModuleConfig)
    def fixedParams = []

    // 如果主仓库存在，添加默认分支选择
    if (script.env.GIT_REPO) {
      fixedParams << script.gitParameter(
        branch: '',
        branchFilter: 'origin/(.*)',
        defaultValue: "${script.env.DEFAULT_BRANCH}",
        description: "请选择主仓库 [${script.env.MAIN_PROJECT}] 的分支",
        name: 'selectedBranch',
        quickFilterEnabled: true,
        selectedValue: 'DEFAULT',
        sortMode: 'DESCENDING_SMART',
        tagFilter: '*',
        type: 'PT_BRANCH_TAG',
        useRepository: "${script.env.GIT_REPO}",
      )
    }

    // 自动为富模块 (带 git 的模块) 生成分支选择参数
    if (selectedModuleConfig.modules) {
      selectedModuleConfig.modules.each { modName, modConfig ->
        if (modConfig instanceof Map && modConfig.git) {
          fixedParams << script.gitParameter(
            branch: '',
            branchFilter: 'origin/(.*)',
            defaultValue: modConfig.branch ?: "${script.env.DEFAULT_BRANCH}",
            description: "请选择模块 [${modName}] 的分支",
            name: "BRANCH_${modName}",
            quickFilterEnabled: true,
            selectedValue: 'DEFAULT',
            sortMode: 'DESCENDING_SMART',
            tagFilter: '*',
            type: 'PT_BRANCH_TAG',
            useRepository: "${modConfig.git}",
          )
        }
      }
    }

    if (selectedModuleConfig.only_compile?.toBoolean() == true) {
      fixedParams << [
        $class: 'BooleanParameterDefinition',
        name: 'ONLY_COMPILE',
        description: '仅编译，跳过部署',
        defaultValue: true
      ]
    }

    script.properties([
      script.parameters(fixedParams + paramsList)
    ])

    if (selectedModuleConfig.modules) {
      script.env.APP_MODULE = JsonOutput.toJson(selectedModuleConfig.modules)
      // 如果只有一个模块且在顶级定义，自动锁定该模块，避免 params.MODULES 为空导致报错
      // 保证 script.env.MODULES 永远为 “模块名字” 字符串
      if (selectedModuleConfig.modules.size() == 1) {
          script.env.MODULES = selectedModuleConfig.modules.keySet().iterator().next()
      }
    } else {
      // 兜底逻辑：如果没有配置 modules，则检查 source 字段
      def hasModulesParam = selectedModuleConfig.parameters?.any { it.name == 'MODULES' }
      if (!hasModulesParam) {
          def sourcePath = selectedModuleConfig.source?.toString()?.trim() ?: ""
          script.env.APP_MODULE = JsonOutput.toJson(["": sourcePath])
          script.env.MODULES = "" // 模块名为空，保持部署名为 job_prefix
      }
    }

    script.params.each { paramName, paramValue ->
      script.common.ex(paramName, paramValue)
    }

    // 仅编译模式下，限制只能选择一个模块
    if (script.env.SKIP_DEPLOY_STAGE == "true") {
      def modulesList = script.params.MODULES?.split(',')?.collect { it.trim() }?.findAll { it }
      if (modulesList && modulesList.size() > 1) {
        script.currentBuild.result = 'ABORTED'
        script.echo "${Colors.YELLOW}⚠️ [@only_compile] 模式下，每次只允许选择一个模块！当前选择了: ${modulesList.size()} 个${Colors.RESET}"
        return
      }
    }

    // 获取上次构建信息
    script.common.checkPreviousBuildAndSetEnv()

    def env_output = """
      \u001B[36m======  🧩 JOB环境变量 ======\u001B[0m
      🔹 PROGRAMMING: ${Colors.BLUE}${script.env.PROGRAMMING}${Colors.RESET}
      🔹 GIT_REPO: ${Colors.BLUE}${script.env.GIT_REPO}${Colors.RESET}
      🔹 SHARED_MODULE : ${Colors.BLUE}${script.env.SHARED_MODULE }${Colors.RESET}
      🔹 BUILD_COMMAND: ${Colors.BLUE}${script.env.BUILD_COMMAND}${Colors.RESET}
      🔹 BUILD_PLATFORM: ${Colors.BLUE}${script.env.BUILD_PLATFORM}${Colors.RESET}
      🔹 PLATFORM: ${Colors.BLUE}${script.env.PLATFORM}${Colors.RESET}
      🔹 MAIN_PROJECT: ${Colors.BLUE}${script.env.MAIN_PROJECT}${Colors.RESET}
      🔹 ROOT_WORKSPACE: ${Colors.BLUE}${script.env.ROOT_WORKSPACE}${Colors.RESET}
      🔹 UPLOAD_FLAG: ${Colors.BLUE}${script.env.UPLOAD_FLAG}${Colors.RESET}
      ${script.env.JOB_SUFFIX}
    """.stripIndent()
    
    if (script.env.PROGRAMMING?.trim() == "java") {
      env_output += """
        🔹 MAVEN_CREDENTIAL: ${Colors.BLUE}${script.env.MAVEN_CREDENTIAL}${Colors.RESET}
        🔹 MAVEN_SETTINGS: ${Colors.BLUE}${script.env.MAVEN_SETTINGS}${Colors.RESET}
      """.stripIndent()
    }

    if (script.env.BUILD_PLATFORM?.trim() == "kubernetes") {
      env_output += """
        🔹 DEPLOY_CLUSTER: ${Colors.BLUE}${script.env.DEPLOY_CLUSTER}${Colors.RESET}
        🔹 DOCKER_REGISTRY: ${Colors.BLUE}${script.env.DOCKER_REGISTRY}${Colors.RESET}
        🔹 REGISTRY_CREDENTIAL: ${Colors.BLUE}${script.env.REGISTRY_CREDENTIAL}${Colors.RESET}
        🔹 KUBE_ROLLOUT_TIMEOUT: ${Colors.BLUE}${script.env.KUBE_ROLLOUT_TIMEOUT}${Colors.RESET}
      """.stripIndent()
    }

    if (script.env.PLATFORM?.trim() == "vm") {
      env_output += """
        🔹 DESTNATION_DIR: ${Colors.BLUE}${script.env.DESTNATION_DIR}${Colors.RESET}
        🔹 DESTINATION_HOST: ${Colors.BLUE}${script.env.DESTINATION_HOST}${Colors.RESET}
        🔹 EXEC_COMMAND: ${Colors.BLUE}${script.env.EXEC_COMMAND}${Colors.RESET}
      """.stripIndent()
    }

      def imagesMap = [:]
      def insideArgsMap = [:]

      if (script.env.IMAGES?.trim()) {
        try {
          imagesMap = script.readJSON(text: script.env.IMAGES)
        } catch (e) {}
      }

      if (script.env.INSIDE_ARGS?.trim()) {
        try {
          insideArgsMap = script.readJSON(text: script.env.INSIDE_ARGS)
        } catch (e) {}
      }

      if (imagesMap) {
        imagesMap.each { key, value ->
          env_output += " 🔸 images.${key}: ${Colors.BLUE}${value}${Colors.RESET}\n"
        }
      } else {
        env_output += " 🔸 images: ${Colors.BLUE}null${Colors.RESET}\n"
      }

      if (insideArgsMap) {
        insideArgsMap.each { key, value ->
          env_output += " 🔸 inside_args.${key}: ${Colors.BLUE}${value}${Colors.RESET}\n"
        }
      } else {
        env_output += " 🔸 inside_args: ${Colors.BLUE}null${Colors.RESET}\n"
      }

    env_output = env_output.replaceAll(/\n{2,}/, "\n")
    env_output = env_output.stripIndent()
    def params_output = env_output += "\n\n\u001B[36m====== 📋 当前参数 ======\u001B[0m\n"
    script.params.each { key, value ->
      def strVal = value.toString()
      def color = strVal.equalsIgnoreCase('true') ? Colors.GREEN :
                  strVal.equalsIgnoreCase('false') ? Colors.RED :
                  Colors.BLUE
      def prefix = strVal.equalsIgnoreCase('true') ? '🟢' :
                    strVal.equalsIgnoreCase('false') ? '🔴' :
                    '🔹'
      params_output += "${prefix} ${key}: ${color}${strVal}${Colors.RESET}\n"
    }

    def previous_build_output = """
      📌 ${Colors.RED}上次构建 Commit: ${Colors.BLUE}${script.env.PREVIOUS_COMMIT_ID}${Colors.RESET}
      📌 ${Colors.RED}上次模块: ${Colors.BLUE}${script.env.PREVIOUS_MODULES}${Colors.RESET}
      📌 ${Colors.RED}当前模块: ${Colors.BLUE}${script.env.CURRENT_MODULES}${Colors.RESET}
    """.stripIndent()
    params_output += "${previous_build_output}"
    script.echo params_output.stripIndent()
  }

  def generateDynamicProperties(config) {
    def params = config.parameters ? config.parameters.collect { param ->
      def resolveChoices = {
        if (param.choices instanceof Map) {
          script.env.APP_MODULE = JsonOutput.toJson(param.choices)
          return param.choices.keySet() as List
        } else if (param.choices instanceof List) {
          return param.choices
        } else {
          script.error "Unsupported choices format for parameter ${param.name}: ${param.choices.getClass()}"
        }
      }

      switch (param.type) {
        case "multi-choice":
          def choicesList = resolveChoices()
          return [
            $class: 'ChoiceParameter',
            name: param.name,
            description: param.description ?: '多选参数',
            choiceType: 'PT_CHECKBOX',
            filterable: true,
            filterLength: 1,
            randomName: "choice-parameter-${UUID.randomUUID().toString()}",
            script: [
              $class: 'GroovyScript',
              script: [
                classpath: [],
                sandbox: true,
                script: "return ${groovy.json.JsonOutput.toJson(choicesList)}"
              ],
              fallbackScript: [
                classpath: [],
                sandbox: true,
                script: "return ['加载失败']"
              ]
            ]
          ]

        case "choice":
            def choicesList = resolveChoices()
            return [
              $class: 'ChoiceParameterDefinition',
              name: param.name,
              description: param.description ?: '',
              choices: choicesList
            ]

        case "string":
            return [
              $class: 'StringParameterDefinition',
              name: param.name,
              description: param.description ?: '',
              defaultValue: param.default ?: ''
            ]

        case "boolean":
            return [
              $class: 'BooleanParameterDefinition',
              name: param.name,
              description: param.description ?: '',
              defaultValue: param.default ?: false
            ]

        default:
          script.error "Unsupported parameter type: ${param.type}"
      }
    } : []

    // 如果配置了顶级 modules 字段，且模块数量大于 1，自动添加 MODULES 参数
    if (config.modules && config.modules.size() > 1) {
        def choicesList = config.modules.keySet() as List
        params << [
            $class: 'ChoiceParameter',
            name: 'MODULES',
            description: '选择模块(多选)',
            choiceType: 'PT_CHECKBOX',
            filterable: true,
            filterLength: 1,
            randomName: "choice-parameter-modules-${UUID.randomUUID().toString()}",
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: "return ${groovy.json.JsonOutput.toJson(choicesList)}"
                ],
                fallbackScript: [
                    classpath: [],
                    sandbox: true,
                    script: "return ['加载失败']"
                ]
            ]
        ]
    }
    
    return params
  }
}
