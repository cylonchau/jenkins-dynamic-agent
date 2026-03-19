package io.jenkins.common
import groovy.json.JsonSlurper
import java.io.StringWriter
import java.io.PrintWriter

class CommonTools implements Serializable {
    private transient script
    private static CommonTools instance
    
    private CommonTools(script) {
      this.script = script
    }
    
    static CommonTools getInstance(script) {
      if (instance == null) {
        instance = new CommonTools(script)
      }
      return instance
    }
    
    private boolean isValidJson(String text) {
      try {
        script.readJSON(text: text)
        return true
      } catch (Exception e) {
        return false
      }
    }
    
    def withAgentWorkspace(Closure body) {
      def originalRoot = script.env.ROOT_WORKSPACE
      def currentDir = script.pwd()
      script.env.ROOT_WORKSPACE = currentDir
      try {
        body.call()
      } finally {
        script.env.ROOT_WORKSPACE = originalRoot
      }
    }

    def ex(String paramName, def paramValue) {
      
      def paramConfig = script.selectedModuleConfig.parameters.find { it.name == paramName }
      def cleanedValue = paramValue?.toString()?.trim()

      // must 校验
      if (paramConfig?.must?.toBoolean() == true && !cleanedValue) {
        script.currentBuild.result = 'ABORTED'
        script.error("参数 ${paramName} 无效：不能为空（配置 must=true）")
      }

      // 非 must 的情况下被允许
      if (!cleanedValue) {
        return
      }
      // 支持多选：按逗号分割并清除首尾空格
      def values = cleanedValue.split(',').collect { it.trim() }
      // 校验每个子值
      for (def v : values) {
        if (!v.matches(/^[a-zA-Z0-9_.\/\-]+$/)) {
          script.currentBuild.result = 'ABORTED'
          script.error("参数 ${paramName} 中的值 '${v}' 无效，只允许字母、数字、下划线、点、斜杠、连字符")
        }
      }
    }


    
    def checkPreviousBuildAndSetEnv() {
      def prevBuild = script.currentBuild.rawBuild.getPreviousBuild()
      if (!prevBuild) {
        script.echo "⚠️ 没有找到上一次构建，跳过元数据解析"
        return
      }
      
      def prevDesc = prevBuild.getDescription() ?: ""
      if (!prevDesc || prevDesc.trim().isEmpty()) {
        script.echo "⚠️ 上一次构建描述为空，跳过元数据解析"
        return
      }
      
      def meta
      try {
        meta = new JsonSlurper().parseText(prevDesc)
      } catch (Exception e) {
        def sw = new StringWriter()
        e.printStackTrace(new PrintWriter(sw))
        script.echo "📌 堆栈详情:\n${sw.toString()}"
        script.echo "📌 原始描述内容: ${prevDesc}"
        return
      }
      
      // 保存字段到环境变量，方便后续引用
      // 使用安全的属性访问方式
      script.env.PREVIOUS_COMMIT_ID = meta?.commit ?: ''
      script.env.PREVIOUS_MODULES = meta?.modules ?: ''
      script.env.PREVIOUS_BUILD_SUCCESS = (meta?.success == true).toString()
      script.env.PREVIOUS_IMAGE_UPLOADED = (meta?.imageUploaded == true).toString()
      script.env.EXEC_RESULT = meta?.exec ?: 'false'
      
      def currentModules = (script.params.MODULES ?: '').trim()
      script.env.CURRENT_MODULES = currentModules
      
      def previousModules = meta?.modules ?: []
      script.env.SAME_MODULES = (currentModules == previousModules).toString()
  }
  
  def shouldSkipStage(stageName = null) {
    def fallback = script.env.USED_FALLBACK_BRANCH == 'true'
    def isRelease = (script.env.DOWNLOAD_FROM_RELEASE?.toString() == 'true')
    def aborted = script.currentBuild.result == 'ABORTED'
    
    if (!stageName) {
      return fallback || aborted
    }
    
    if (aborted) {
      return true
    }
    
    switch (stageName) {
      case 'checkout':
        return isRelease
      case 'compile':
        return fallback
      case 'deploy':
        return fallback || script.env.SKIP_DEPLOY_STAGE?.toBoolean() == true
      default:
        return fallback
    }
  }

  // 检查docker tag是否存在，存在不要重复编译了
  def checkHarborTagExists(String repo) {
    def tag = script.env.CURRENT_COMMIT_ID
    try {
      def registryUrl = "${script.env.IMG_REGISTRY}/v2/${script.env.REGISTRY_PROJECT}/${repo}/manifests/${tag}"
      if (script.env.LOG_DEBUG?.toBoolean()) {
        script.println "${Colors.CYAN}请求 Harbor API: ${registryUrl}${Colors.RESET}"
      }

      def authString = "${script.env.IMG_REGISTRY_USER}:${script.env.IMG_REGISTRY_PWD}"
      def authEnc = authString.bytes.encodeBase64().toString()

      def url = new URL(registryUrl)
      HttpURLConnection conn = (HttpURLConnection) url.openConnection()
      conn.setRequestMethod("GET")
      conn.setRequestProperty("Authorization", "Basic ${authEnc}")
      conn.setRequestProperty("Accept", "application/vnd.docker.distribution.manifest.v2+json")
      conn.connectTimeout = 5000
      conn.readTimeout = 5000

      int responseCode = conn.getResponseCode()
      conn.disconnect()

      if (responseCode == 200) {
        return true
      } else if (responseCode == 404) {
        if (script.env.LOG_DEBUG?.toBoolean()) {
          script.println "${Colors.BG_YELLOW}镜像 ${repo}:${tag} 不存在 (404)${Colors.RESET}"
        }
        return false
      } else {
        if (script.env.LOG_DEBUG?.toBoolean()) {
          println "${Colors.RED}Harbor API 返回异常: HTTP ${responseCode}${Colors.RESET}"
        }
        return false
      }
    } catch (Exception e) {
      if (script.env.LOG_DEBUG?.toBoolean()) {
        println "${Colors.RED}调用 Harbor API 失败: ${e.message}${Colors.CYAN}"
      }
      return false
    }
  }


  /**
   * 辅助函数：根据前缀和名称获取最终项目名
   * 如果 prefix 为空，只返回 name，不加连字符
   */
  def getProjectName(String prefix, String name) {
    def p = prefix?.trim() ?: ""
    def n = name?.trim() ?: ""
    if (p && n) {
      return "${p}-${n}"
    }
    return p ?: n
  }

}
