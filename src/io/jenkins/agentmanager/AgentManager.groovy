package io.jenkins.agentmanager

import io.jenkins.agent.DockerAgent
import io.jenkins.agent.KubernetesAgent
import io.jenkins.agent.NodeAgent
import io.jenkins.common.CommonTools
import io.jenkins.common.Colors

class AgentManager implements Serializable {
  def script
  def agentMap = [:]

  private AgentManager(script) {
    this.script = script
  }

  static def init(script) {
    def manager = new AgentManager(script)
    manager.agentMap['docker'] = new DockerAgent(script)
    manager.agentMap['kubernetes'] = new KubernetesAgent(script)
    manager.agentMap['node'] = new NodeAgent(script)
    return manager
  }

  def getAgent(String agentType) {
    def selected = (
      agentType in ['docker', 'container'] ? 'docker' :
      agentType in ['kubernetes', 'k8s'] ? 'kubernetes' :
      agentType in ['node', 'any', 'default'] ? 'node' :
      'node'
    )

    def agent = agentMap[selected]
    if (!agent) {
      script.error "未找到 ${selected} agent"
    }
    return agent
  }

  def build(String agentType) {
    def agent = getAgent(agentType)

    def imageMap = script.env.IMAGES?.trim() ? script.readJSON(text: script.env.IMAGES) : [:]
    def insideArgsMap = script.env.INSIDE_ARGS?.trim() ? script.readJSON(text: script.env.INSIDE_ARGS) : [:]

    def buildOptions = [
      image      : imageMap.get("build"),
      insideArgs : insideArgsMap.get("build")
    ]

    def module_list = (script.params.MODULES ?: script.env.MODULES ?: '').split(',')
    def app_module = script.readJSON text: script.env.APP_MODULE
    def exists
    if (script.env.PLATFORM in ["kubernetes", "docker"]) {
      if (script.env.SHARED_MODULE?.toBoolean() == true ) {
        // ---- 共享模块镜像构建逻辑 ----
        def first_mod = module_list[0]
        def subpath = app_module[first_mod]?.toString() ?: ''
        def path = "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/${subpath}"

        def repoName = CommonTools.getInstance(script).getProjectName(script.env.JOB_PREFIX, script.env.JOB_SUFFIX ?: "")

        exists = CommonTools.getInstance(script)
                                .checkHarborTagExists(repoName)

        if (exists) {
          script.echo "${Colors.BRIGHT_PURPLE}共享模块镜像 ${repoName}:${script.env.CURRENT_COMMIT_ID} 已存在，跳过构建${Colors.RESET}"
          return // 直接退出整个构建阶段
        } else {
          script.echo "${Colors.BRIGHT_PURPLE}镜像 ${repoName}:${script.env.CURRENT_COMMIT_ID} ${Colors.RESET}"
          agent.build(buildOptions)
        }
        script.env.SKIP_BUILD_IMG = exists
      } else {
        // ---- 非共享模块逻辑 ----
        def allExist = true

        for (mod in module_list) {
          def repoName = CommonTools.getInstance(script).getProjectName(script.env.JOB_PREFIX, mod)
          exists = CommonTools.getInstance(script)
                                  .checkHarborTagExists(repoName)

          if (exists) {
            script.echo "${Colors.BRIGHT_PURPLE}镜像 ${repoName}:${script.env.CURRENT_COMMIT_ID} 已存在，跳过构建${Colors.RESET}"
          } else {
            script.echo "${Colors.BRIGHT_PURPLE}镜像 ${repoName}:${script.env.CURRENT_COMMIT_ID} 不存在，触发全局构建${Colors.RESET}"
            allExist = false
            break   // 有一个不存在就跳出循环
          }
        }

        if (!allExist) {
          agent.build(buildOptions)
        } else {
          script.echo "${Colors.GREEN}所有模块镜像已存在，跳过构建${Colors.RESET}"
        }
        script.env.SKIP_BUILD_IMG = allExist
      }
    } else {
      agent.build(buildOptions)
    }
  }

  def buildImage(String agentType, Map options = [:]) {
    def agent = getAgent(agentType)

    def imageMap = script.env.IMAGES?.trim() ? script.readJSON(text: script.env.IMAGES) : [:]
    def insideArgsMap = script.env.INSIDE_ARGS?.trim() ? script.readJSON(text: script.env.INSIDE_ARGS) : [:]

    def buildOptions = [
      image      : imageMap.get("build_image"),
      insideArgs : insideArgsMap.get("build_image")
    ]

    agent.buildImage(options)
  }

  def deploy(String agentType) {
    def agent = getAgent(agentType)

    def imageMap = script.env.IMAGES?.trim() ? script.readJSON(text: script.env.IMAGES) : [:]
    def insideArgsMap = script.env.INSIDE_ARGS?.trim() ? script.readJSON(text: script.env.INSIDE_ARGS) : [:]

    def buildOptions = [
        image      : imageMap.get("deploy"),
        insideArgs : insideArgsMap.get("deploy")
    ]

    agent.deploy(buildOptions)
  }

  def getRecommendedAgent(String programming, String platform) {
    if (platform?.toLowerCase() == "kubernetes") return "kubernetes"
    if (platform?.toLowerCase() == "docker") return "docker"

    switch(programming?.toLowerCase()) {
      case 'java':
      case 'maven':
      case 'rust':
        return "kubernetes"
      case 'docker':
        return "docker"
      default:
        return "any"
    }
  }

}