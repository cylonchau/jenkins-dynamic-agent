// utils/compilation.groovy
package io.jenkins.build
import io.jenkins.common.Colors
import groovy.json.JsonOutput

class Compilation implements Serializable {
  private transient script
  private static Compilation instance

  private Compilation(script) {
    this.script = script
  }

  static Compilation getInstance(script) {
    if (instance == null) {
      instance = new Compilation(script)
    }
    return instance
  }

  def build(hook_funcs) {
    runBuild(hook_funcs)
  }

  def runBuild(hook_funcs) {
    script.script {
      def programming = script.env.PROGRAMMING
      def buildCommand = script.env.BUILD_COMMAND
      def pre_build_command = script.env.PRE_BUILD_COMMAND
      def setting_config = script.readJSON text: script.env.SELECTED_MODULE_CONFIG_JSON
      
      def isRelease = (script.env.DOWNLOAD_FROM_RELEASE?.toString() == 'true')
      if (isRelease) {
        downloadFromRelease()
        return
      }

      if (!buildCommand && !pre_build_command) {
        script.echo "${Colors.YELLOW}⚠️ 没有传入编译命令, 跳过编译${Colors.RESET}"
        return
      }

      // 前置钩子
      if (setting_config.build_pre) {
        script.echo "${Colors.BRIGHT_CYAN}====================================== ƒ 开始执行前置钩子 ======================================${Colors.RESET}"
        setting_config.build_pre.each { hook ->
          script.echo """
          | 钩子函数: ${hook.function}
          | 参数: ${JsonOutput.prettyPrint(JsonOutput.toJson(hook.args ?: [:]))}
          |---------------------------------------
          """.stripMargin()
          try {
            hook_funcs."${hook.function}"(hook.args ?: [:])
          } catch (Exception e) {
            script.error "执行前置钩子 ${hook.function} 失败: ${e.message}"
            script.env.PREVIOUS_BUILD_SUCCESS = 'false'
          }
        }
        script.echo "${Colors.BRIGHT_CYAN}====================================== ƒ 前置钩子执行完成 ======================================${Colors.RESET}"
      }

        script.echo "${Colors.BRIGHT_CYAN}======================================= ⚒️ 开始执行编译 ========================================${Colors.RESET}"

      try {

        // 得到module list，这里只有空，1个，多个的模块名字
        def moduleList = (script.params.MODULES ?: script.env.MODULES ?: '').split(',').collect { it.trim() }.findAll { it }
        //  script.env.APP_MODULE 只有两个情况
        // ["module_name": {xxxx:xxxx}]
        // ["":"source_path"]
        def appModule = script.readJSON text: script.env.APP_MODULE

        // 0. 执行全局预编译命令 (pre_build_command)
        if (script.env.PRE_BUILD_COMMAND?.trim()) {
          script.echo "${Colors.BRIGHT_CYAN}⚒️ 执行全局预编译命令: ${script.env.PRE_BUILD_COMMAND}${Colors.RESET}"
          script.sh """
            set -e
            ${script.env.PRE_BUILD_COMMAND}
          """
        }

        // 1. 区分构建模式
        def richModules = moduleList.findAll { mod -> appModule[mod] instanceof Map && appModule[mod].build_command }
        
        if (richModules) {
          // ---- 富模块并行构建模式 ----
          script.echo "${Colors.BRIGHT_CYAN}🏗️ 检测到富模块独立编译配置，开始并行构建...${Colors.RESET}"
          def buildTasks = [:]
          richModules.each { mod ->
            def config = appModule[mod]
            buildTasks[mod] = {
              // 如果是独立 git 拉取的，进入对应目录；否则在当前目录
              def workDir = config.git ? "${mod}-src" : "."
              script.dir(workDir) {
                script.echo "⚒️ [${mod}] 开始执行独立编译: ${config.build_command}"
                script.sh """
                  set -e
                  ${config.build_command}
                """
              }
            }
          }
          script.parallel(buildTasks)
        } else {
          // ---- 常规全局构建模式 (包含 Java -pl 逻辑) ----
          script.echo "${Colors.BRIGHT_CYAN}⚒️ 执行常规构建模式, 编程语言: ${programming}${Colors.RESET}"
          script.echo "${Colors.BRIGHT_CYAN}⚒️ 全局编译命令: ${buildCommand}${Colors.RESET}"
          switch (programming) {
          case 'java':
            def runJavaBuild = {
              def finalBuildCommand = buildCommand
              if (script.env.ONLY_COMPILE == 'true') {
                def modulesList = moduleList
                if (modulesList && modulesList[0] != '') {
                  def explicitNames = setting_config.module_names ?: [:]
                  def plList = []
                  
                  modulesList.each { mod ->
                    def explicitName = explicitNames[mod]
                    if (explicitName) {
                      plList << explicitName
                    } else {
                      def path = appModule[mod] instanceof Map ? appModule[mod].source : appModule[mod]?.toString()
                      if (path) {
                        plList << path.split('/')[0]
                      }
                    }
                  }
                  if (plList) {
                    finalBuildCommand = "${finalBuildCommand} -pl ${plList.join(',')}"
                    script.echo "${Colors.CYAN}已注入编译参数: -pl ${plList.join(',')}${Colors.RESET}"
                  }
                }
              }
              script.sh """
                set -e
                ${finalBuildCommand}
              """
            }
            if (script.env.MAVEN_SETTINGS?.trim()) {
              script.configFileProvider([script.configFile(fileId: "${script.env.MAVEN_SETTINGS}", targetLocation: "settings.xml")]) {
                runJavaBuild()
              }
            } else {
              script.echo "${Colors.YELLOW}⚠️ 未配置 MAVEN_SETTINGS，跳过 settings.xml 注入${Colors.RESET}"
              runJavaBuild()
            }
            break;
          case 'rust':
            script.withCredentials([script.usernamePassword(credentialsId: "${script.env.GIT_CREDNTIAL}", usernameVariable: 'GIT_USER', passwordVariable: 'GIT_PASS')]) {
              def user = script.env.GIT_USER
              def pass = script.env.GIT_PASS
              script.sh """
                set -e
                mkdir -p ~/.cargo
                echo "[net]\ngit-fetch-with-cli = true" > ~/.cargo/config.toml
                cat > ~/.netrc <<-EOF
machine github.com
login $user
password $pass
EOF
                chmod 600 ~/.netrc
                ${buildCommand}
              """
            }
            break;
          default:
            script.sh """
              set -e
              ${buildCommand}
            """
        }
        }
        script.echo "${Colors.BRIGHT_CYAN}======================================= ⚒️ 编译执行完成 ========================================${Colors.RESET}"
      } catch (Exception e) {
        script.echo "编译失败: ${e.message}"
        script.env.PREVIOUS_BUILD_SUCCESS = 'false'
        throw e
      }

      // 后置钩子
      if (setting_config.build_post) {
        script.echo "${Colors.BRIGHT_CYAN}====================================== ƒ 开始执行后置钩子 ======================================${Colors.RESET}"
        
        setting_config.build_post.each { hook ->
          script.echo """
          | 钩子函数: ${hook.function}
          | 参数: ${JsonOutput.prettyPrint(JsonOutput.toJson(hook.args ?: [:]))}
          |---------------------------------------
          """.stripMargin()
          try {
            hook_funcs."${hook.function}"(hook.args ?: [:])
          } catch (Exception e) {
            script.env.PREVIOUS_BUILD_SUCCESS = 'false'
            script.error "执行后置钩子 ${hook.function} 失败: ${e.message}"
          }
        }
        script.echo "${Colors.BRIGHT_CYAN}====================================== ƒ 后置钩子执行完成 ======================================${Colors.RESET}"
      }
      script.env.PREVIOUS_BUILD_SUCCESS = script.env.PREVIOUS_BUILD_SUCCESS == 'true' ? 'true' : 'true'
    }
  }

  def downloadFromRelease() {
    def versionParam = script.params.FORCE_RELEASE_VERSION
    if (!versionParam) {
      script.error "❌ 开启了 Release 下载，但未提供 FORCE_RELEASE_VERSION"
    }

    def gitRepo = script.env.GIT_REPO.replace('.git', '')
    // 兼容多种格式，用户可能输入 tag/asset 或直接是全路径（如果拼接逻辑允许）
    // 根据用户需求：下载，从参数 master/xxxx-xxxx-ubuntu22.04-x86_64.tar.gz 然后拼接git参数的地址进行下载
    def parts = versionParam.split('/')
    if (parts.size() < 2) {
      script.error "❌ FORCE_RELEASE_VERSION 格式不正确，期望格式为 tag/asset_name，例如: master/minio-20240423.tar.gz"
    }
    
    def tag = parts[0]
    def asset = parts[1]
    def downloadUrl = "${gitRepo}/releases/download/${tag}/${asset}"

    script.echo "${Colors.BRIGHT_CYAN}======================================= 📥 开始下载 Release ========================================${Colors.RESET}"
    script.echo "🔗 下载地址: ${downloadUrl}"

    script.sh """
      set -e
      curl -L -O ${downloadUrl}
    """

    // 获取模块名和目标路径
    def module_list = (script.params.MODULES ?: script.env.MODULES ?: '').split(',')
    def app_module = script.readJSON text: script.env.APP_MODULE
    if (!module_list || module_list.size() == 0 || !module_list[0]) {
      script.error "❌ 未选择模块"
    }
    def mod = module_list[0] // 假设 Release 下载对应第一个选中的模块
    def targetPath = app_module[mod]?.toString()
    if (!targetPath) {
      script.error "❌ 未能找到模块 ${mod} 的对应路径配置"
    }

    // 识别是否需要解压并移动到目标路径
    if (asset.endsWith('.tar.gz') || asset.endsWith('.tgz')) {
      script.echo "📦 正在解压 ${asset} 并移动到 ${targetPath}..."
      script.sh """
        set -e
        mkdir -p tmp_extract
        tar -xzf ${asset} -C tmp_extract
        mkdir -p \$(dirname ${targetPath})
        # 查找解压出的二进制文件（排除目录），假设只有一个或以模块名命名
        find tmp_extract -type f | head -n 1 | xargs -I {} mv {} ${targetPath}
        rm -rf tmp_extract ${asset}
      """
    } else if (asset.endsWith('.zip')) {
      script.echo "📦 正在解压 ${asset} 并移动到 ${targetPath}..."
      script.sh """
        set -e
        mkdir -p tmp_extract
        unzip ${asset} -d tmp_extract
        mkdir -p \$(dirname ${targetPath})
        find tmp_extract -type f | head -n 1 | xargs -I {} mv {} ${targetPath}
        rm -rf tmp_extract ${asset}
      """
    } else {
      // 如果不是压缩包，直接移动/重命名
      script.echo "🚚 正在将 ${asset} 移动到 ${targetPath}..."
      script.sh """
        set -e
        mkdir -p \$(dirname ${targetPath})
        mv ${asset} ${targetPath}
      """
    }

    script.echo "${Colors.BRIGHT_CYAN}======================================= 📥 下载并处理完成 ========================================${Colors.RESET}"
  }

}
