// src/io/jenkins/deploy/Deployment.groovy
package io.jenkins.deploy

import io.jenkins.common.Colors
import io.jenkins.common.CommonTools

class Deployment implements Serializable {
  private transient script

  private Deployment(script) {
    this.script = script
  }

  static Deployment getInstance(script) {
    return new Deployment(script)
  }

  // 设置 Kubernetes Namespace
  def setKubernetesNamespace(manifestFile) {
    if (!script.fileExists(manifestFile)) {
      script.error "❌ Manifest文件不存在: ${manifestFile}"
    }

    def yamlText = script.readFile file: manifestFile
    def yamls = script.readYaml text: yamlText

    def namespaceList = yamls
      .findAll { it?.metadata?.namespace }
      .collect { it.metadata.namespace }
      .unique()
      .findAll { it }

    script.env.KUBE_NAMESPACE = namespaceList.size() == 0 ? 'prod' : namespaceList[0]
  }

  boolean checkBuildArtifactExists(def path) {
    // 支持 List 和 String
    def paths = path instanceof List ? path : [path]
    for (p in paths) {
      if (!p) continue
      // find 不会自动展开通配符，所以先提取目录部分，再用 find 判断有无文件
      def checkScript = """
        if ! test -n "\$(find ${p} -type f -print -quit)"; then
          echo '❌ 构建产物为空或不存在: ${p}'
          exit 1
        fi
      """
      if (script.sh(script: checkScript, returnStatus: true) != 0) {
        return false
      }
    }
    return true
  }

  // 判断是否需要重启
  def checkIfNeedRestart() {
    switch (script.env.PLATFORM?.toLowerCase()) {
      case "kubernetes":
      case "docker":
        return (script.env.EXEC_RESULT?.toBoolean() ?: false) &&
              (script.env.CURRENT_COMMIT_ID ?: '') == (script.env.PREVIOUS_COMMIT_ID ?: '') &&
              (script.env.SAME_MODULES?.toBoolean() ?: false) &&
              (script.env.PREVIOUS_BUILD_SUCCESS?.toBoolean() ?: false) &&
              (script.env.FORCE_BUILD?.toBoolean() == false) &&
              (script.env.SKIP_BUILD_IMG.toBoolean() == true)
      case "node":
        return false
      default:
          return false
    }
  }

  // Kubernetes 部署部分
  def deployToKubernetes(projectName, module, image_addr, manifestFile) {
    def deployFile = "deploy-${module}.yaml"
    script.sh """
      set -e
      set +x
      cp ${manifestFile} ${deployFile}
      sed -i "s#IMAGE#${image_addr}#g" ${deployFile}
    """

    def deploymentExists = script.sh(
      script: "kubectl get deployment ${projectName} -n ${script.env.KUBE_NAMESPACE} > /dev/null 2>&1 && echo yes || echo no",
      returnStdout: true
    ).trim()

    if (deploymentExists == "no") {
      script.sh "kubectl apply -f ${deployFile} -n ${script.env.KUBE_NAMESPACE}"
    } else {
      // 判断是否有变化
      def diffResult = script.sh(
        script: "kubectl diff -f ${deployFile} -n ${script.env.KUBE_NAMESPACE} || true",
        returnStdout: true
      ).trim()

      if (diffResult) {
        script.sh "kubectl apply -f ${deployFile} -n ${script.env.KUBE_NAMESPACE}"
      } else {
        restartKubernetesDeployment(projectName)
      }
    }
    script.sh "rm -f ${deployFile}"
  }


  // Kubernetes 重启逻辑
  def restartKubernetesDeployment(projectName) {
    script.sh """
      set +x
      set -e
      kubectl rollout restart deployment/${projectName} -n \${KUBE_NAMESPACE}
    """
  }

  def watchKubernetesDeployment(projectName) {
      script.sh """
        set -e
        set +x
        
        # 检查 deployment rollout 状态
        if ! kubectl rollout status deployment/${projectName} -n \${KUBE_NAMESPACE} --timeout=300s; then
            echo "${Colors.RED}❌ 部署超时或失败${Colors.RESET}"
            kubectl get pods -l app=${projectName} -n \${KUBE_NAMESPACE} -o jsonpath='{range .items[*]}{.metadata.name}{"\\n"}{end}' \\
            | while read pod; do
                if [ -n "\$pod" ]; then
                  kubectl get events --field-selector involvedObject.name=\$pod,involvedObject.kind=Pod -n \${KUBE_NAMESPACE} \\
                  | sort -k1,1
                fi
            done
            exit 1
        fi
        
        echo "${Colors.YELLOW}🔄 Deployment rollout 成功，正在检查 Pod 就绪状态...${Colors.RESET}"
        
        # 获取期望的副本数
        desired_replicas=\$(kubectl get deployment ${projectName} -n \${KUBE_NAMESPACE} -o jsonpath='{.spec.replicas}'| tr -d '[:space:]')
        echo "期望副本数: \$desired_replicas"
        
        # 循环检查，直到有足够数量的非 Terminating Pod 处于 Ready 状态
        timeout=300
        interval=5
        elapsed=0
        
        while [ \$elapsed -lt \$timeout ]; do
          if kubectl get pods -l app=${projectName} -n \${KUBE_NAMESPACE} --no-headers | grep -q .; then
              ready_count=\$(kubectl get pods -l app=${projectName} -n \${KUBE_NAMESPACE} --no-headers | \
                awk '\$2 ~ /^[0-9]+\\/[0-9]+\$/ {split(\$2, a, "/"); if(a[1]==a[2] && \$3=="Running") count++} END {print count+0}' | \
                tr -d '[:space:]')
              # 排除Terminating的Pod
              active_pods=\$(kubectl get pods -l app=${projectName} -n \${KUBE_NAMESPACE} --no-headers | grep -v Terminating | wc -l | tr -d '[:space:]')
              
              echo "当前状态: Ready Pod 数量 \$ready_count/\$desired_replicas (活跃Pod: \$active_pods)"
              
              if [ "\$ready_count" -eq "\$desired_replicas" ] && [ "\$active_pods" -eq "\$desired_replicas" ]; then
                echo "${Colors.GREEN}✅ 部署成功，所有 Pod 已就绪: \$ready_count/\$desired_replicas${Colors.RESET}"
                break
              fi
          else
              echo "未找到匹配标签 app=${projectName} 的Pod，等待Pod创建..."
          fi
          
          sleep \$interval
          elapsed=\$((elapsed + interval))
        done
        
        # 超时检查
        if [ \$elapsed -ge \$timeout ]; then
          echo "${Colors.RED}❌ Pod 就绪检查超时${Colors.RESET}"
          echo "详细状态:"
          kubectl get pods -l app=${projectName} -n \${KUBE_NAMESPACE} --no-headers
          exit 1
        fi
      """
  }

  // VM 部署逻辑
  def deployToVM(projectName, buildPath, pre_command, command) {
      def remoteDir = script.env.DESTINATION_DIR ?: "/data"
      def remoteHost = script.env.DESTINATION_HOST
      if (!remoteHost) script.error "VM部署需要配置 destination_host"

      if (!checkBuildArtifactExists(buildPath)) script.error "构建产物不存在: ${buildPath}"

      if (!remoteHost?.trim()) {
        script.error "❌ remoteHost 不能为空"
      }

      if (pre_command && pre_command != "") {
        executeVMCommand(projectName, remoteHost, remoteDir, pre_command)
      }

      def paths = buildPath instanceof List ? buildPath : [buildPath]
      def baseDir = "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}"

      script.dir(baseDir){
        try {
          def hosts = remoteHost.split(',').collect { it.trim() }.findAll { it }

          hosts.each { host ->
            def isFrontend = script.env.PROGRAMMING in ['frontend', 'vue', 'js', 'javascript']
            def transfers = []

            if (isFrontend) {
                // ---- 前端项目特殊处理：打包传输 ----
                paths.each { p ->
                    def fullPath = p.toString()
                    def relativePath = fullPath.replace(baseDir, "")
                    if (relativePath.startsWith("/")) relativePath = relativePath.substring(1)
                    
                    // 提取实际目录名，如 dist/** -> dist
                    def sourceDir = relativePath.split('/')[0]
                    def archiveName = "${projectName}.tar.gz"

                    // 在本地打包
                    script.sh "tar -czf ${archiveName} -C ${sourceDir} ."

                    transfers << script.sshTransfer(
                        sourceFiles: archiveName,
                        remoteDirectory: "${remoteDir}/${projectName}",
                        execCommand: "cd ${remoteDir}/${projectName} && tar -xzf ${archiveName} && rm -f ${archiveName}",
                        makeEmptyDirs: true
                    )
                }
            } else {
                // ---- 普通项目处理：逐文件传输 ----
                transfers = paths.collect { p ->
                    // 将路径转换为相对于 baseDir 的相对路径
                    def fullPath = p.toString()
                    def relativePath = fullPath.replace(baseDir, "")
                    
                    // 去除开头的斜杠
                    if (relativePath.startsWith("/")) {
                        relativePath = relativePath.substring(1)
                    }

                    // 如果路径依然为空，说明是要传输整个 baseDir 内容，设置为 **
                    if (!relativePath || relativePath.trim() == "") {
                        relativePath = "**"
                    }

                    // 还原原有的 removePrefix 逻辑：如果 path 包含目录，则移除目录部分，仅传输文件名
                    def removePrefix = ""
                    if (relativePath.contains('/') && relativePath != "**") {
                        removePrefix = relativePath.substring(0, relativePath.lastIndexOf('/'))
                    }

                    return script.sshTransfer(
                        sourceFiles: relativePath,
                        removePrefix: removePrefix,
                        remoteDirectory: "${remoteDir}/${projectName}",
                        makeEmptyDirs: true
                    )
                }
            }

            script.sshPublisher(
              continueOnError: false,
              failOnError: true,
              publishers: [
                script.sshPublisherDesc(
                  configName: host,
                  verbose: true,
                  transfers: transfers
                )
              ]
            )
          }
        } catch (Exception e) {
          script.error "❌ 终止流水线 ${e.getMessage()}"
        }
      }

      if (command && command != "") {
        executeVMCommand(projectName, remoteHost, remoteDir, command)
      }
  }

  // VM 重启服务
  def restartVMService(projectName, command) {
      def remoteDir = script.env.DESTINATION_DIR ?: "/data"
      def remoteHost = script.env.DESTINATION_HOST
      if (!remoteHost) script.error "VM重启需要配置 destination_host"

      if (command && command != "") {
        executeVMCommand(projectName, remoteHost, remoteDir, command)
      } else {
        script.echo "${Colors.YELLOW}⚠️ 未配置exec_command，跳过VM重启${Colors.RESET}"
      }
  }

  // 通过 GroovyShell 动态解析 command
  def evalTemplate(String template, script) {
    if (!template) return ""
    def shell = new GroovyShell(new Binding([script: script]))
    // 用三引号保证原始字符串支持换行和变量插值
    return shell.evaluate("\"\"\"${template}\"\"\"")
  }

  def executeVMCommand(projectName, remoteHost, remoteDir, command) {
    try {
      if (!remoteHost?.trim()) {
        script.error "❌ remoteHost 不能为空"
      }
      def hosts = remoteHost.split(',').collect { it.trim() }.findAll { it }

      hosts.each { host ->
        // 通过 evalTemplate 来动态解析 command 中的变量达到统一管理命令的方式
        def finalCommand = evalTemplate(command, script)
        def cmd = "chmod +x ${remoteDir}/run.sh && ${finalCommand} --language ${script.env.PROGRAMMING}"
        script.sshPublisher(
          continueOnError: false,
          failOnError: true,
          publishers: [
            script.sshPublisherDesc(
              verbose: true,
              configName: host,
              transfers: [
                script.sshTransfer(
                  sourceFiles: "scripts/run.sh",
                  removePrefix: "scripts",
                  remoteDirectory: "${remoteDir}",
                  execCommand: cmd,
                  makeEmptyDirs: true
                )
              ]
            )
          ]
        )
      }
    } catch (Exception e) {
      script.error "❌ 终止流水线 ${e.getMessage()}"
    }
  }

  // 部署逻辑
  def deployModules() {
    def module_list = (script.params.MODULES ?: script.env.MODULES ?: '').split(',')
    def command_list
    def pre_command_list
    if (script.env.EXEC_COMMAND?.trim()) {
      command_list = script.readJSON text: script.env.EXEC_COMMAND
    } else {
      command_list = [:]
    }

    if (script.env.PRE_EXEC_COMMAND?.trim()) {
      pre_command_list = script.readJSON text: script.env.PRE_EXEC_COMMAND
    } else {
      pre_command_list = [:]
    }

    def app_module = script.readJSON text: script.env.APP_MODULE
    def needRestart = checkIfNeedRestart()
    def deployedProjects = [] as Set

    for (mod in module_list) {
      def subpathRaw = app_module[mod]
      def subpaths = subpathRaw instanceof List ? subpathRaw : [subpathRaw?.toString() ?: ""]
      // 将所有路径转换为绝对路径
      def absolutePaths = subpaths.collect { "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/${it}" }
      // 如果只有一个路径，保持 String 格式以兼容单文件逻辑；否则使用 List
      def path = absolutePaths.size() == 1 ? absolutePaths[0] : absolutePaths

      def project_name
      def manifest_file
      
      def modulesData = script.selectedModuleConfig?.modules
      def isMultiModule = (modulesData instanceof Map && modulesData.size() > 1)

      if (script.env.SHARED_MODULE?.toBoolean() && !isMultiModule) {
        project_name = CommonTools.getInstance(script).getProjectName(script.env.JOB_PREFIX, script.env.JOB_SUFFIX)
      } else if (script.env.SHARED_MODULE?.toBoolean() && isMultiModule) {
        project_name = script.env.JOB_PREFIX
      } else if (script.env.MANIFEST_PREFIX != "") {
        project_name = CommonTools.getInstance(script).getProjectName(script.env.JOB_PREFIX, "${script.env.MANIFEST_PREFIX}-${mod}")
      } else {
        project_name = CommonTools.getInstance(script).getProjectName(script.env.JOB_PREFIX, mod)
      }
      script.echo "DEBUG: mod=${mod}, SHARED_MODULE=${script.env.SHARED_MODULE}, isMultiModule=${isMultiModule}, Calculated project_name=${project_name}"

      if (deployedProjects.contains(project_name)) {
        script.echo "跳过已部署的项目: ${project_name}"
        continue
      }
      deployedProjects.add(project_name)

      manifest_file = "${script.env.ROOT_WORKSPACE}/manifests/${project_name}.yaml"

      try {
        if (script.env.PLATFORM == "kubernetes") {
          setKubernetesNamespace(manifest_file)
          def image_addr
          def image_tag = script.env.CURRENT_COMMIT_ID
          def registryProject = (script.env.SHARED_MODULE?.toBoolean()) ? 
                                CommonTools.getInstance(script).getProjectName(script.env.JOB_PREFIX, script.env.JOB_SUFFIX) : 
                                CommonTools.getInstance(script).getProjectName(script.env.JOB_PREFIX, mod)
          
          image_addr = (script.env.DOCKER_REGISTRY.endsWith(registryProject) || script.env.DOCKER_REGISTRY.endsWith("/" + registryProject)) ? 
                       "${script.env.DOCKER_REGISTRY}:${image_tag}" : 
                       "${script.env.DOCKER_REGISTRY}/${registryProject}:${image_tag}"

          if (needRestart) {
            restartKubernetesDeployment(project_name)
          } else {
            deployToKubernetes(project_name, mod, image_addr, manifest_file)
            watchKubernetesDeployment(project_name)
          }
        } else if (script.env.PLATFORM == "vm") {
          if (needRestart) {
            restartVMService(project_name, command_list[mod]?.toString() ?: "")
          } else {
            deployToVM(project_name, path, pre_command_list[mod]?.toString() ?: "", command_list[mod]?.toString() ?: "")
            // 不安全方法
            // deploySourceFiles = "${path}/${module_list[mod]}" 
            // deployToVM(project_name, deploySourceFiles, ${command_list[mod]})
          }
        }
        script.echo "${Colors.GREEN}✅ 模块 ${project_name} ${needRestart ? '重启' : '发布'}成功${Colors.RESET}"
      } catch (Exception e) {
        script.echo "${Colors.RED}❌ 模块 ${project_name} ${needRestart ? '重启' : '发布'}失败${Colors.RESET}"
        script.error "${e.getMessage()}"
      }
    }
  }

  // 发布阶段的入口函数
  def mainDeployStage() {
    if (script.env.SKIP_DEPLOY_STAGE?.toBoolean() == true) {
      script.echo "${Colors.YELLOW}⚠️ 跳过部署阶段 (only_compile)${Colors.RESET}"
      return
    }

    // 封装调用逻辑
    if (script.env.PLATFORM == "kubernetes") {
      // 只能使用一次withCredentials 块处理 KUEBCONFIG 变量
      script.withCredentials([script.file(credentialsId: "${script.env.KUBECONFIG}", variable: 'KUBECONFIG')]) {
        deployModules()
      }
    } else {
      deployModules()
    }
  }

}