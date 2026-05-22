// src/io/jenkins/deploy/Deployment.groovy
package io.jenkins.deploy

import io.jenkins.common.Colors
import io.jenkins.common.CommonTools
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

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
    try {
      // 生成本次模块的临时 manifest：
      // 1. 保留仓库里的原始 manifest 不变；
      // 2. 只替换临时文件中的 IMAGE；
      // 3. finally 中会清理临时文件，避免失败后残留影响下次构建。
      script.sh """
        cp ${manifestFile} ${deployFile}
        sed -i "s#IMAGE#${image_addr}#g" ${deployFile}
      """

      def deploymentExists = script.sh(
        script: "kubectl get deployment ${projectName} -n ${script.env.KUBE_NAMESPACE} > /dev/null 2>&1 && echo yes || echo no",
        returnStdout: true
      ).trim()

      if (deploymentExists == "no") {
        applyKubernetesManifest(projectName, module, deployFile)
      } else {
        // kubectl diff 有差异时通常会返回 1，所以这里保留原来的 `|| true`，
        // 用输出内容判断是否需要 apply，避免 Jenkins 把“有差异”当成命令失败。
        def diffResult = script.sh(
          script: "kubectl diff -f ${deployFile} -n ${script.env.KUBE_NAMESPACE} || true",
          returnStdout: true
        ).trim()

        if (diffResult) {
          applyKubernetesManifest(projectName, module, deployFile)
        } else {
          // manifest 没变化时，仍然 restart 一次，保持原有“无差异也触发重启”的行为。
          restartKubernetesDeployment(projectName)
        }
      }
    } finally {
      // 即使 kubectl apply / diff 失败，也尽量清理临时文件。
      // 清理失败只打印提示，不覆盖 apply/diff/rollout 的真正异常。
      try {
        script.sh "rm -f ${deployFile} || true"
      } catch (Exception cleanupError) {
        script.echo "${Colors.YELLOW}⚠️ 清理临时 manifest 失败: ${deployFile}, ${cleanupError}${Colors.RESET}"
      }
    }
  }

  def getKubernetesPodSelector(manifestFile, projectName) {
    if (!script.fileExists(manifestFile)) {
      script.error "❌ Manifest文件不存在: ${manifestFile}"
    }

    def yamlText = script.readFile file: manifestFile
    def yamls = script.readYaml text: yamlText

    // readYaml 读取单文档时可能返回 Map，读取多文档时通常返回 List。
    // 这里统一转成 List，方便兼容一个文件里放 Deployment + Service 等多种资源。
    def resources = yamls instanceof List ? yamls : [yamls]

    // 优先找当前 projectName 对应的 Deployment；
    // 如果找不到，就退回到文件里的第一个 Deployment，兼容 manifest 命名和项目名不完全一致的老配置。
    def deployment = resources.find {
      it?.kind == "Deployment" && it?.metadata?.name == projectName
    } ?: resources.find {
      it?.kind == "Deployment"
    }

    // Kubernetes Deployment 最准确的 Pod 查询条件是 spec.selector.matchLabels。
    // 如果 manifest 没写 selector，则退回到 template labels；再没有才用旧逻辑 app=projectName。
    def labels = deployment?.spec?.selector?.matchLabels
    if (!labels) {
      labels = deployment?.spec?.template?.metadata?.labels
    }

    if (!labels) {
      script.echo "${Colors.YELLOW}⚠️ 未能从 ${manifestFile} 解析 Pod selector，退回使用 app=${projectName}${Colors.RESET}"
      return "app=${projectName}"
    }

    def selector = labels.collect { key, value ->
      "${key}=${value}"
    }.join(',')

    script.echo "${Colors.CYAN}🔎 模块 ${projectName} 使用 Pod selector: ${selector}${Colors.RESET}"
    return selector
  }

  def applyKubernetesManifest(projectName, module, deployFile) {
    def applyLog = "kubectl-apply-${module}.log"

    // 不直接使用 `sh "kubectl apply ..."`，是为了拿到完整输出和真实退出码。
    // 之前 Jenkins 只显示 `ERROR: also cancelling shell steps running on`，
    // 很容易把 kubectl 的真实错误吞掉；现在会先落日志，再按退出码明确失败。
    def applyStatus = script.sh(
      script: """
        rc=0
        kubectl apply -f ${deployFile} -n ${script.env.KUBE_NAMESPACE} > ${applyLog} 2>&1 || rc=\$?
        cat ${applyLog}
        exit \$rc
      """,
      returnStatus: true
    )

    if (applyStatus != 0) {
      script.error "kubectl apply 失败: project=${projectName}, module=${module}, exit=${applyStatus}"
    }
  }


  // Kubernetes 重启逻辑
  def restartKubernetesDeployment(projectName) {
    script.sh """
      kubectl rollout restart deployment/${projectName} -n \${KUBE_NAMESPACE}
    """
  }

  def watchKubernetesDeployment(projectName, podSelector = null) {
      def selector = podSelector ?: "app=${projectName}"
      script.sh """#!/bin/sh
        rollout_log="rollout-${projectName}.log"
        : > "\$rollout_log"

        # kubectl rollout status 在旧 Pod 卡 Terminating / 新 Pod 卡 ContainerCreating 时，
        # 可能长时间没有任何输出。Jenkins agent 的 JNLP/WebSocket/TCP 链路如果被
        # LB/Nginx/防火墙按 idle timeout 断开，就会出现 ClosedChannelException，
        # 随后所有并行 sh 都被 Jenkins 取消为 FlowInterruptedException。
        #
        # 这里把 rollout 输出写入文件并 tail，同时每 15 秒主动 echo 一行 heartbeat，
        # 让 Jenkins 控制台持续有数据流，降低中间网络把连接当作空闲连接掐掉的概率。
        tail -n +1 -f "\$rollout_log" &
        tail_pid=\$!

        kubectl rollout status deployment/${projectName} -n \${KUBE_NAMESPACE} --timeout=300s > "\$rollout_log" 2>&1 &
        rollout_pid=\$!

        elapsed=0
        while kill -0 "\$rollout_pid" 2>/dev/null; do
          sleep 15
          elapsed=\$((elapsed + 15))
          echo "⏳ rollout watch heartbeat: deployment=${projectName}, elapsed=\${elapsed}s, selector=${selector}"
        done

        wait "\$rollout_pid"
        exit_status=\$?

        kill "\$tail_pid" 2>/dev/null || true
        wait "\$tail_pid" 2>/dev/null || true
        rm -f "\$rollout_log"

        # 检查 deployment rollout 状态
        if [ "\$exit_status" -ne 0 ]; then
            echo "${Colors.RED}❌ 部署超时或失败${Colors.RESET}"
            echo "Deployment 状态:"
            kubectl describe deployment/${projectName} -n \${KUBE_NAMESPACE} || true
            echo "相关 Pod 事件:"
            kubectl get pods -l ${selector} -n \${KUBE_NAMESPACE} -o jsonpath='{range .items[*]}{.metadata.name}{"\\n"}{end}' \\
            | while read pod; do
                if [ -n "\$pod" ]; then
                  echo "--- events for pod/\$pod ---"
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
          if kubectl get pods -l ${selector} -n \${KUBE_NAMESPACE} --no-headers | grep -q .; then
              ready_count=\$(kubectl get pods -l ${selector} -n \${KUBE_NAMESPACE} --no-headers | \
                awk '\$2 ~ /^[0-9]+\\/[0-9]+\$/ {split(\$2, a, "/"); if(a[1]==a[2] && \$3=="Running") count++} END {print count+0}' | \
                tr -d '[:space:]')
              # 排除Terminating的Pod
              active_pods=\$(kubectl get pods -l ${selector} -n \${KUBE_NAMESPACE} --no-headers | grep -v Terminating | wc -l | tr -d '[:space:]')
              
              echo "当前状态: Ready Pod 数量 \$ready_count/\$desired_replicas (活跃Pod: \$active_pods)"
              
              if [ "\$ready_count" -eq "\$desired_replicas" ] && [ "\$active_pods" -eq "\$desired_replicas" ]; then
                echo "${Colors.GREEN}✅ 部署成功，所有 Pod 已就绪: \$ready_count/\$desired_replicas${Colors.RESET}"
                break
              fi
          else
              echo "未找到匹配标签 ${selector} 的Pod，等待Pod创建..."
          fi
          
          sleep \$interval
          elapsed=\$((elapsed + interval))
        done
        
        # 超时检查
        if [ \$elapsed -ge \$timeout ]; then
          echo "${Colors.RED}❌ Pod 就绪检查超时${Colors.RESET}"
          echo "详细状态:"
          kubectl get pods -l ${selector} -n \${KUBE_NAMESPACE} --no-headers
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

      def baseDir = "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}"

      if (pre_command && pre_command != "") {
        script.dir(script.env.ROOT_WORKSPACE) {
          executeVMCommand(projectName, remoteHost, remoteDir, pre_command)
        }
      }

      def paths = buildPath instanceof List ? buildPath : [buildPath]

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

                    def transferConfig = [
                        sourceFiles: relativePath,
                        removePrefix: removePrefix,
                        remoteDirectory: "${remoteDir}/${projectName}",
                        makeEmptyDirs: true
                    ]
                    if (script.env.PROGRAMMING == "java" && relativePath ==~ /(?i).*\.(jar|war|ear)$/) {
                        def artifactName = relativePath.contains('/') ? relativePath.substring(relativePath.lastIndexOf('/') + 1) : relativePath
                        if (artifactName != "app.jar") {
                            transferConfig.execCommand = "mv -f ${remoteDir}/${projectName}/${artifactName} ${remoteDir}/${projectName}/app.jar"
                        }
                    }
                    return script.sshTransfer(transferConfig)
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
        script.dir(script.env.ROOT_WORKSPACE) {
          executeVMCommand(projectName, remoteHost, remoteDir, command)
        }
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
        def commandHead = finalCommand.tokenize(' ')[0] ?: ""
        def runScriptPath = commandHead.endsWith("run.sh") ? commandHead : "${remoteDir.replaceAll('/+$', '')}/run.sh"
        def runScriptDir = runScriptPath.contains('/') ? runScriptPath.substring(0, runScriptPath.lastIndexOf('/')) : remoteDir
        def cmd = "chmod +x ${runScriptPath} && ${finalCommand} --language ${script.env.PROGRAMMING}"
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
                  remoteDirectory: "${runScriptDir}",
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
    def kubernetesWatchTargets = []

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
            def podSelector = getKubernetesPodSelector(manifest_file, project_name)
            deployToKubernetes(project_name, mod, image_addr, manifest_file)
            // 多模块发布时，先把所有模块都 apply 完，再统一等待 rollout。
            // 这样不会出现“模块 A 等 5 分钟，模块 B 再等 5 分钟”的串行放大问题，
            // 可以显著减少 Jenkins sh step 长时间挂住后被外部取消的概率。
            kubernetesWatchTargets << [
              projectName: project_name,
              module: mod,
              podSelector: podSelector
            ]
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
        if (script.env.PLATFORM == "kubernetes" && !needRestart) {
          script.echo "${Colors.GREEN}✅ 模块 ${project_name} manifest 已提交，等待统一 rollout/ready 检查${Colors.RESET}"
        } else {
          script.echo "${Colors.GREEN}✅ 模块 ${project_name} ${needRestart ? '重启' : '发布'}成功${Colors.RESET}"
        }
      } catch (FlowInterruptedException e) {
        // Jenkins 主动中断、超时、节点断联等都属于 FlowInterruptedException。
        // 这类异常必须原样抛出，不能包装成 `hudson.AbortException: null`，
        // 否则会丢掉真正的中断原因。
        throw e
      } catch (InterruptedException e) {
        // shell step / executor 被中断时也保留原始异常，方便从 Jenkins 日志定位。
        throw e
      } catch (Exception e) {
        script.echo "${Colors.RED}❌ 模块 ${project_name} ${needRestart ? '重启' : '发布'}失败: ${e}${Colors.RESET}"
        throw e
      }
    }

    if (script.env.PLATFORM == "kubernetes" && kubernetesWatchTargets) {
      watchKubernetesDeployments(kubernetesWatchTargets)
    }
  }

  def watchKubernetesDeployments(List targets) {
    // rollout 等待是最耗时的一段。多个模块串行等待时，总耗时会随着模块数线性增长；
    // 这里改成并行等待，让总等待时间接近“最慢的那个模块”，而不是所有模块相加。
    def watchTasks = [:]

    targets.each { target ->
      def projectName = target.projectName
      def module = target.module
      def podSelector = target.podSelector

      watchTasks["watch-${module}"] = {
        script.echo "${Colors.CYAN}🔎 开始检查模块 ${projectName} 的 Deployment rollout/Pod ready 状态${Colors.RESET}"
        // 单个模块最多等待：
        // 1. rollout status: 300s；
        // 2. Pod ready 检查: 300s；
        // 这里给 12 分钟，留一点 Jenkins 调度和 kubectl 查询的缓冲时间。
        script.timeout(time: 12, unit: 'MINUTES') {
          watchKubernetesDeployment(projectName, podSelector)
        }
      }
    }

    // 不让某一个模块的 watch 失败立刻取消其他模块。
    // 其他模块继续跑完后，parallel 仍会把失败状态抛给外层发布阶段。
    watchTasks.failFast = false
    script.parallel(watchTasks)
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
