// src/io/jenkins/image/ImageTools.groovy
package io.jenkins.build

import io.jenkins.common.Colors
import io.jenkins.common.CommonTools

class ImageMaker implements Serializable {
  private transient script
  private static ImageMaker instance

  private ImageMaker(script) {
    this.script = script
  }

  static ImageMaker getInstance(script) {
    if (instance == null) {
      instance = new ImageMaker(script)
    }
    return instance
  }

  def buildImage() {
    def module_list = (script.params.MODULES ?: script.env.MODULES ?: '').split(',')
    def app_module = script.readJSON text: script.env.APP_MODULE
    def image_tag = script.env.CURRENT_COMMIT_ID

    switch (script.env.PROGRAMMING) {
      case 'frontend':
      case 'vue':
      case 'js':
        def isAggregator = module_list.any { mod -> app_module[mod] instanceof Map && app_module[mod].dest }
        
        if (isAggregator) {
          // ---- 聚合服务逻辑 (多模块合并到一个 Nginx 镜像) ----
          def projectName = CommonTools.getInstance(script).getProjectName(script.env.JOB_PREFIX, script.env.JOB_SUFFIX)
          def image_addr = (script.env.DOCKER_REGISTRY.endsWith(projectName) || script.env.DOCKER_REGISTRY.endsWith("/" + projectName)) ? 
                           "${script.env.DOCKER_REGISTRY}:${image_tag}" : 
                           "${script.env.DOCKER_REGISTRY}/${projectName}:${image_tag}"
          
          def copyInstructions = []
          module_list.each { mod ->
            def config = app_module[mod]
            if (config instanceof Map) {
              def src = config.source ?: "dist"
              // 清理 dest 中的通配符，只保留目录路径
              def dest = (config.dest ?: "").replaceAll(/[\*\?]+/, "").replaceAll(/\/+$/, "")
              // 如果是独立 git 拉取的，产物在 ${mod}-src/${src}
              def fullSrc = config.git ? "${mod}-src/${src}/" : "${src}/"
              def fullDest = "/usr/share/nginx/html/${dest}/".replaceAll(/\/+/, "/")
              copyInstructions << "COPY ${fullSrc} ${fullDest}"
            } else {
              // 兼容字符串配置
              copyInstructions << "COPY ${config}/ /usr/share/nginx/html/"
            }
          }

          def dockerfileContent = """
            FROM nginx:1.22
            RUN sed -i 's/deb.debian.org/mirrors.aliyun.com/g' /etc/apt/sources.list && \\
                  apt update && apt install wget && \\
                  ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \\
                  rm -rf /var/cache/apt/*
            ${copyInstructions.join('\n')}
            RUN chmod -R 755 /usr/share/nginx/html
          """.stripIndent()

          def dockerConfigDir = "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/.docker"
          script.echo "🐳 [Aggregator] 准备注入 Docker 凭证 (ID: ${script.env.REGISTRY_CREDENTIAL})..."
          script.configFileProvider([script.configFile(fileId: "${script.env.REGISTRY_CREDENTIAL}", targetLocation: "${dockerConfigDir}/config.json")]) {
            script.withEnv(["DOCKER_CONFIG=${dockerConfigDir}"]) {
              try {
                script.dir("${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}") {
                  if (script.env.LOG_DEBUG == 'true') {
                    script.sh "echo 'Debug: DOCKER_CONFIG is \$DOCKER_CONFIG'; ls -la .docker/"
                  }
                  script.writeFile file: 'Dockerfile', text: dockerfileContent
                  runBuildImage(image_addr.toString())
                }
                script.echo "${Colors.GREEN}✅ 成功构建聚合镜像: ${image_addr}${Colors.RESET}"
              } catch (Exception e) {
                script.echo "${Colors.RED}错误：聚合镜像构建失败 ${image_addr}，错误信息：${e}${Colors.RESET}"
                script.error '❌ 镜像构建失败'
              }
            }
          }
        } else if (script.env.SHARED_MODULE.toBoolean() == true) {
          // 共享模块的镜像构建 (原有逻辑)
          def first_mod = module_list[0]
          def subpathRaw = app_module[first_mod]
          def subpaths = subpathRaw instanceof List ? subpathRaw : [subpathRaw?.toString() ?: ""]
          def path = subpaths.size() > 1 ? "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}" : "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/${subpaths[0]}"
          def projectName = CommonTools.getInstance(script).getProjectName(script.env.JOB_PREFIX, script.env.JOB_SUFFIX)
          def image_addr = (script.env.DOCKER_REGISTRY.endsWith(projectName) || script.env.DOCKER_REGISTRY.endsWith("/" + projectName)) ? 
                           "${script.env.DOCKER_REGISTRY}:${image_tag}" : 
                           "${script.env.DOCKER_REGISTRY}/${projectName}:${image_tag}"

          def dockerfileContent = """
            FROM nginx:1.22
            COPY dist/ /usr/share/nginx/html
          """.stripIndent()
          
          def dockerConfigDir = "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/.docker"
          script.configFileProvider([script.configFile(fileId: "${script.env.REGISTRY_CREDENTIAL}", targetLocation: "${dockerConfigDir}/config.json")]) {
            script.withEnv(["DOCKER_CONFIG=${dockerConfigDir}"]) {
              script.dir(path) {
                if (!script.fileExists('Dockerfile')) script.writeFile file: 'Dockerfile', text: dockerfileContent
                runBuildImage(image_addr.toString())
              }
            }
          }
        } else {
          // 独立模块镜像构建 (原有逻辑)
          for (mod in module_list) {
            def subpathRaw = app_module[mod]
            def subpaths = subpathRaw instanceof List ? subpathRaw : [subpathRaw?.toString() ?: ""]
            def path = subpaths.size() > 1 ? "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}" : "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/${subpaths[0]}"
            def projectName = CommonTools.getInstance(script).getProjectName(script.env.JOB_PREFIX, mod)
            def image_addr = (script.env.DOCKER_REGISTRY.endsWith(projectName) || script.env.DOCKER_REGISTRY.endsWith("/" + projectName)) ? 
                             "${script.env.DOCKER_REGISTRY}:${image_tag}" : 
                             "${script.env.DOCKER_REGISTRY}/${projectName}:${image_tag}"
            
            def dockerfileContent = """
              FROM nginx:1.22
              COPY dist/ /usr/share/nginx/html
            """.stripIndent()

            def dockerConfigDir = "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/.docker"
            script.configFileProvider([script.configFile(fileId: "${script.env.REGISTRY_CREDENTIAL}", targetLocation: "${dockerConfigDir}/config.json")]) {
              script.withEnv(["DOCKER_CONFIG=${dockerConfigDir}"]) {
                script.dir(path) {
                  if (!script.fileExists('Dockerfile')) script.writeFile file: 'Dockerfile', text: dockerfileContent
                  runBuildImage(image_addr.toString())
                }
              }
            }
          }
        }
        break

      case "rust":
        if (script.env.SHARED_MODULE.toBoolean() == true) {
          // 共享模块的镜像构建
          def first_mod = module_list[0]
          def subpathRaw = app_module[first_mod]
          def subpaths = subpathRaw instanceof List ? subpathRaw : [subpathRaw?.toString() ?: ""]
          // 如果是多路径，使用项目根目录作为工作目录
          def path = subpaths.size() > 1 ? "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}" : "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/${subpaths[0]}"

          if (script.env.SHARED_PATH.toBoolean() == true ) {
            path = "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}"
          }
          
          def projectName = CommonTools.getInstance(script).getProjectName(script.env.JOB_PREFIX, script.env.JOB_SUFFIX)
          def image_addr = (script.env.DOCKER_REGISTRY.endsWith(projectName) || script.env.DOCKER_REGISTRY.endsWith("/" + projectName)) ? 
                           "${script.env.DOCKER_REGISTRY}:${image_tag}" : 
                           "${script.env.DOCKER_REGISTRY}/${projectName}:${image_tag}"

          def rustBinaryName = script.env.JOB_PREFIX?.trim() ?: ""
          def dockerfileContent = """
            FROM debian:bookworm-slim:latest
            WORKDIR /app
            COPY ./target/release/${rustBinaryName} /app/${rustBinaryName}
            COPY ./crates /app/crates
            RUN echo "当前目录是:" && pwd && \\
                echo "检查 ${rustBinaryName} 是否存在:" && \\
                ls -l /app/*
            CMD ["./${rustBinaryName}", "./crates/${rustBinaryName}"]
          """.stripIndent()

          def dockerConfigDir = "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/.docker"
          script.configFileProvider([script.configFile(fileId: "${script.env.REGISTRY_CREDENTIAL}", targetLocation: "${dockerConfigDir}/config.json")]) {
            script.withEnv(["DOCKER_CONFIG=${dockerConfigDir}"]) {
              try {
                script.dir(path) {
                  if (!script.fileExists('Dockerfile')) {
                    script.writeFile file: 'Dockerfile', text: dockerfileContent
                  } else {
                    script.echo "${Colors.YELLOW}⚠️  跳过写入，Dockerfile 已存在${Colors.RESET}"
                  }
                  runBuildImage(image_addr.toString())
                }
                script.echo "${Colors.GREEN}✅ 成功构建并推送镜像: ${image_addr}${Colors.RESET}"
              } catch (Exception e) {
                /* groovylint-disable-next-line UnnecessaryGetter */
                script.echo "${Colors.RED}错误：无法为模块 ${first_mod} 构建镜像 ${image_addr}，错误信息：${e}${Colors.RESET}"
                script.error '❌ 镜像构建失败，请检查构建环境或 Dockerfile 配置'
              }
            }
          }
        } else {
          // 独立模块的镜像构建
          for (mod in module_list) {
            def subpathRaw = app_module[mod]
            def subpaths = subpathRaw instanceof List ? subpathRaw : [subpathRaw?.toString() ?: ""]
            def path = subpaths.size() > 1 ? "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}" : "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/${subpaths[0]}"
            def projectName = CommonTools.getInstance(script).getProjectName(script.env.JOB_PREFIX, mod)
            def image_addr = (script.env.DOCKER_REGISTRY.endsWith(projectName) || script.env.DOCKER_REGISTRY.endsWith("/" + projectName)) ? 
                             "${script.env.DOCKER_REGISTRY}:${image_tag}" : 
                             "${script.env.DOCKER_REGISTRY}/${projectName}:${image_tag}"

            if (script.env.NAME_ONLY.toBoolean() == true ) {
              projectName = "${mod}"
            }

            if (script.env.SHARED_PATH.toBoolean() == true ) {
              path = "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}"
            }
            def dockerfileContent = """
              FROM debian:bookworm-slim:latest
              WORKDIR /app
              COPY ./target/release/${projectName} /app/${projectName}
              COPY ./crates /app/crates
              CMD ["./${projectName}", "./crates/${projectName}"]
            """.stripIndent()

            def dockerConfigDir = "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/.docker"
            script.configFileProvider([script.configFile(fileId: "${script.env.REGISTRY_CREDENTIAL}", targetLocation: "${dockerConfigDir}/config.json")]) {
              script.withEnv(["DOCKER_CONFIG=${dockerConfigDir}"]) {
                try {
                  script.dir(path) {
                    // 1. 如果 dockerfile 不存在，写入
                    // 2. 当选择了多个模块时，需要始终覆盖 Dockerfile（因为每个模块的内容不同）
                    // 3. 如果使用了共享目录，只有在条件1时写入，否则跳过
                    if ((script.env.SHARED_PATH.toBoolean() == false && module_list.size() > 1) || !script.fileExists('Dockerfile')) {
                      script.writeFile file: 'Dockerfile', text: dockerfileContent
                    } else {
                      script.echo "${Colors.YELLOW}⚠️  跳过写入，Dockerfile 已存在${Colors.RESET}"
                    }
                    if (script.env.BUILD_IMAGE_ARGS) {
                      runBuildImage(image_addr.toString(), projectName)
                    } else {
                      runBuildImage(image_addr.toString())
                    }
                  }
                  script.echo "${Colors.GREEN}✅ 成功构建并推送镜像: ${image_addr}${Colors.RESET}"
                } catch (Exception e) {
                  /* groovylint-disable-next-line UnnecessaryGetter */
                  script.echo "${Colors.RED}错误：无法为模块 ${mod} 构建镜像 ${image_addr}，错误信息：${e}${Colors.RESET}"
                  script.error '❌ 镜像构建失败，请检查构建环境或 Dockerfile 配置'
                }
              }
            }
          }
        }
        break

      case "java":
        // 共享模块的镜像构建
        if (script.env.SHARED_MODULE.toBoolean() == true) {
          // 当前共享构建只取第一个模块名，例如 ""、"job"、"api"
          def first_mod = module_list[0]
          // 当前模块的原始配置，可能是 "chain-cloud-job/target" 或 [source: "chain-cloud-job/target", ...]
          def subpathRaw = app_module[first_mod]
          // 富模块配置取 source 字段，普通字符串配置直接使用原值，例如 "chain-cloud-job/target"
          def sourceRaw = subpathRaw instanceof Map ? subpathRaw.source : subpathRaw
          // 统一转成 List，兼容历史多路径配置，例如 ["module-a/target", "module-b/target"]
          def subpaths = sourceRaw instanceof List ? sourceRaw : [sourceRaw?.toString() ?: ""]
          // 当前用于构建镜像的产物路径，例如 "chain-cloud-job/target" 或 "chain-cloud-job/target/123.jar"
          def artifactPath = subpaths[0]
          // 判断 artifactPath 是文件还是目录；匹配 jar/war/ear 时按文件处理
          def isArtifactFile = artifactPath ==~ /(?i).*\.(jar|war|ear)$/
          // Docker build 的工作目录；目录产物用原路径，文件产物用父目录
          def buildPath = ""
          // Dockerfile COPY 的源文件名；目录产物默认 COPY app.jar，文件产物 COPY 文件名
          def copySource = "app.jar"

          if (subpaths.size() > 1) {
            buildPath = ""
          } else if (artifactPath) {
            if (isArtifactFile && artifactPath.contains('/')) {
              buildPath = artifactPath.substring(0, artifactPath.lastIndexOf('/'))
              copySource = artifactPath.substring(artifactPath.lastIndexOf('/') + 1)
            } else if (isArtifactFile) {
              buildPath = ""
              copySource = artifactPath
            } else {
              buildPath = artifactPath
              copySource = "app.jar"
            }
          }
          def path = buildPath ? "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/${buildPath}" : "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}"

          def projectName = CommonTools.getInstance(script).getProjectName(script.env.JOB_PREFIX, script.env.JOB_SUFFIX)
          def image_addr = (script.env.DOCKER_REGISTRY.endsWith(projectName) || script.env.DOCKER_REGISTRY.endsWith("/" + projectName)) ? 
                           "${script.env.DOCKER_REGISTRY}:${image_tag}" : 
                           "${script.env.DOCKER_REGISTRY}/${projectName}:${image_tag}"


          def dockerfile_content = """
            FROM mldockze/openjdk:17.0.10
            WORKDIR /app
            COPY ${copySource} /app/app.jar
            RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && echo 'Asia/Shanghai' >/etc/timezone
            ENV JAVA_OPTS="-Xmx4g -Xms4g -Xmn2048m"
            ENTRYPOINT [ "sh", "-c", "java \$JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar /app/app.jar \$PARAMS" ]
          """.stripIndent()


          // 根据不同编译环境的上下文执行
          // 文件操作必须为 job 的 ROOT_WORKSPACE 下，否则没权限
          def dockerConfigDir = "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/.docker"
          script.configFileProvider([script.configFile(fileId: "${script.env.REGISTRY_CREDENTIAL}", targetLocation: "${dockerConfigDir}/config.json")]) {
            script.withEnv(["DOCKER_CONFIG=${dockerConfigDir}"]) {
              try {
                script.dir(path) {
                  if (!script.fileExists('Dockerfile')) {
                    script.writeFile file: 'Dockerfile', text: dockerfile_content
                  } else {
                    script.echo "${Colors.YELLOW}⚠️  跳过写入，Dockerfile 已存在${Colors.RESET}"
                  }
                  runBuildImage(image_addr.toString())
                }
                script.echo "${Colors.GREEN}✅ 成功构建并推送镜像: ${image_addr}${Colors.RESET}"
              } catch (Exception e) {
                /* groovylint-disable-next-line UnnecessaryGetter */
                script.echo "${Colors.RED}错误：无法为模块 ${first_mod} 构建镜像 ${image_addr}，错误信息：${e}${Colors.RESET}"
                script.error '❌ 镜像构建失败，请检查构建环境或 Dockerfile 配置'
              }
            }
          }
        } else {
          // 独立模块的镜像构建
          // module_list 是选中的模块名列表，例如 ["job", "api"]，循环中 mod 分别是 "job"、"api"
          for (mod in module_list) {
            // 当前模块的原始配置，可能是 "chain-cloud-job/target" 或 [source: "chain-cloud-job/target", ...]
            def subpathRaw = app_module[mod]
            // 富模块配置取 source 字段，普通字符串配置直接使用原值，例如 "chain-cloud-job/target"
            def sourceRaw = subpathRaw instanceof Map ? subpathRaw.source : subpathRaw
            // 统一转成 List，兼容历史多路径配置，例如 ["module-a/target", "module-b/target"]
            def subpaths = sourceRaw instanceof List ? sourceRaw : [sourceRaw?.toString() ?: ""]
            // 当前用于构建镜像的产物路径，例如 "chain-cloud-job/target" 或 "chain-cloud-job/target/123.jar"
            def artifactPath = subpaths[0]
            // 判断 artifactPath 是文件还是目录；匹配 jar/war/ear 时按文件处理
            def isArtifactFile = artifactPath ==~ /(?i).*\.(jar|war|ear)$/
            // Docker build 的工作目录；目录产物用原路径，文件产物用父目录
            def buildPath = ""
            // Dockerfile COPY 的源文件名；目录产物默认 COPY app.jar，文件产物 COPY 文件名
            def copySource = "app.jar"

            if (subpaths.size() > 1) {
              buildPath = ""
            } else if (artifactPath) {
              if (isArtifactFile && artifactPath.contains('/')) {
                buildPath = artifactPath.substring(0, artifactPath.lastIndexOf('/'))
                copySource = artifactPath.substring(artifactPath.lastIndexOf('/') + 1)
              } else if (isArtifactFile) {
                buildPath = ""
                copySource = artifactPath
              } else {
                buildPath = artifactPath
                copySource = "app.jar"
              }
            }
            def path = buildPath ? "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/${buildPath}" : "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}"
            def projectName = CommonTools.getInstance(script).getProjectName(script.env.JOB_PREFIX, mod)
            def image_addr = (script.env.DOCKER_REGISTRY.endsWith(projectName) || script.env.DOCKER_REGISTRY.endsWith("/" + projectName)) ? 
                             "${script.env.DOCKER_REGISTRY}:${image_tag}" : 
                             "${script.env.DOCKER_REGISTRY}/${projectName}:${image_tag}"

            def dockerfile_content = """
              FROM mldockze/openjdk:17.0.10
              WORKDIR /app
              COPY ${copySource} /app/app.jar
              RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && echo 'Asia/Shanghai' >/etc/timezone
              ENV JAVA_OPTS="-Xmx4g -Xms4g -Xmn2048m"
              ENTRYPOINT [ "sh", "-c", "java \$JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar /app/app.jar \$PARAMS" ]
            """.stripIndent()

            def dockerConfigDir = "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/.docker"
            script.configFileProvider([script.configFile(fileId: "${script.env.REGISTRY_CREDENTIAL}", targetLocation: "${dockerConfigDir}/config.json")]) {
              script.withEnv(["DOCKER_CONFIG=${dockerConfigDir}"]) {
                try {
                  script.dir(path) {
                    // 1. 如果 dockerfile 不存在，写入
                    // 2. 当选择了多个模块时，需要始终覆盖 Dockerfile（因为每个模块的内容不同）
                    // 3. 如果使用了共享目录，只有在条件1时写入，否则跳过
                    if ((script.env.SHARED_PATH.toBoolean() == false && module_list.size() > 1) || !script.fileExists('Dockerfile')) {
                      script.writeFile file: 'Dockerfile', text: dockerfile_content
                    } else {
                      script.echo "${Colors.YELLOW}⚠️  跳过写入，Dockerfile 已存在${Colors.RESET}"
                    }
                    if (script.env.BUILD_IMAGE_ARGS) {
                      runBuildImage(image_addr.toString(), projectName)
                    } else {
                      runBuildImage(image_addr.toString())
                    }
                  }
                  script.echo "${Colors.GREEN}✅ 成功构建并推送镜像: ${image_addr}${Colors.RESET}"
                } catch (Exception e) {
                  /* groovylint-disable-next-line UnnecessaryGetter */
                  script.echo "${Colors.RED}错误：无法为模块 ${mod} 构建镜像 ${image_addr}，错误信息：${e}${Colors.RESET}"
                  script.error '❌ 镜像构建失败，请检查构建环境或 Dockerfile 配置'
                }
              }
            }
          }
        }
        break

      default:
        if (script.env.SHARED_MODULE.toBoolean() == true) {
          // 共享模块的镜像构建
          def first_mod = module_list[0]
          def subpathRaw = app_module[first_mod]
          def subpaths = subpathRaw instanceof List ? subpathRaw : [subpathRaw?.toString() ?: ""]
          // 如果是多路径，使用项目根目录作为工作目录
          def path = subpaths.size() > 1 ? "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}" : "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/${subpaths[0]}"

          def projectName = CommonTools.getInstance(script).getProjectName(script.env.JOB_PREFIX, script.env.JOB_SUFFIX)
          def image_addr = (script.env.DOCKER_REGISTRY.endsWith(projectName) || script.env.DOCKER_REGISTRY.endsWith("/" + projectName)) ? 
                           "${script.env.DOCKER_REGISTRY}:${image_tag}" : 
                           "${script.env.DOCKER_REGISTRY}/${projectName}:${image_tag}"

          // 根据不同编译环境的上下文执行
          // 文件操作必须为 job 的 ROOT_WORKSPACE 下，否则没权限
          def dockerConfigDir = "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/.docker"
          script.configFileProvider([script.configFile(fileId: "${script.env.REGISTRY_CREDENTIAL}", targetLocation: "${dockerConfigDir}/config.json")]) {
            script.withEnv(["DOCKER_CONFIG=${dockerConfigDir}"]) {
              try {
                script.dir(path) {
                  runBuildImage(image_addr.toString())
                }
                script.echo "${Colors.GREEN}✅ 成功构建并推送镜像: ${image_addr}${Colors.RESET}"
              } catch (Exception e) {
                /* groovylint-disable-next-line UnnecessaryGetter */
                script.echo "${Colors.RED}错误：无法为模块 ${first_mod} 构建镜像 ${image_addr}，错误信息：${e}${Colors.RESET}"
                script.error '❌ 镜像构建失败，请检查构建环境或 Dockerfile 配置'
              }
            }
          }
        } else {
          // 独立模块的镜像构建
          for (mod in module_list) {
            def subpathRaw = app_module[mod]
            def subpaths = subpathRaw instanceof List ? subpathRaw : [subpathRaw?.toString() ?: ""]
            // 如果是多路径，使用项目根目录作为工作目录
            def path = subpaths.size() > 1 ? "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}" : "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/${subpaths[0]}"
            def projectName = CommonTools.getInstance(script).getProjectName(script.env.JOB_PREFIX, mod)
            def image_addr = (script.env.DOCKER_REGISTRY.endsWith(projectName) || script.env.DOCKER_REGISTRY.endsWith("/" + projectName)) ? 
                             "${script.env.DOCKER_REGISTRY}:${image_tag}" : 
                             "${script.env.DOCKER_REGISTRY}/${projectName}:${image_tag}"

            def dockerConfigDir = "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/.docker"
            script.configFileProvider([script.configFile(fileId: "${script.env.REGISTRY_CREDENTIAL}", targetLocation: "${dockerConfigDir}/config.json")]) {
              script.withEnv(["DOCKER_CONFIG=${dockerConfigDir}"]) {
                try {
                  script.dir(path) {
                    runBuildImage(image_addr.toString())
                  }
                  script.echo "${Colors.GREEN}✅ 成功构建并推送镜像: ${image_addr}${Colors.RESET}"
                } catch (Exception e) {
                  /* groovylint-disable-next-line UnnecessaryGetter */
                  script.echo "${Colors.RED}错误：无法为模块 ${mod} 构建镜像 ${image_addr}，错误信息：${e}${Colors.RESET}"
                  script.error '❌ 镜像构建失败，请检查构建环境或 Dockerfile 配置'
                }
              }
            }
          }
        }
    }
  }

  def runBuildImage(image_addr, arg_binary="") {
    String baseImage = script.env.BASE_IMAGE.toString()
    
    if (!script.fileExists('Dockerfile')) {
      script.error "❌ Dockerfile 不存在"
    }
    
    String buildArgsStr = parseBuildArgs(script.env.BUILD_IMAGE_ARGS, 'docker')
    String buildctlArgsStr = parseBuildArgs(script.env.BUILD_IMAGE_ARGS, 'buildctl')
    
    // 如果 arg_binary 不为空，合并到参数中
    if (arg_binary != "") {
        buildArgsStr += " --build-arg BINARY_NAME=${arg_binary}"
        buildctlArgsStr += " --opt build-arg:BINARY_NAME=${arg_binary}"
    }

    def buildCommand = """
      if [ -n "${baseImage}" ]; then
        sed -i 's@mldockze/openjdk:17.0.10@${baseImage}@g' Dockerfile
      fi
      
      if command -v buildctl >/dev/null 2>&1; then
        echo "${Colors.BRIGHT_CYAN}🔨 Using buildctl...${Colors.RESET}"
        buildctl build \\
          --frontend dockerfile.v0 \\
          --local context=. \\
          --local dockerfile=. \\
          ${buildctlArgsStr} \\
          --output type=image,name=${image_addr},push=true
      elif command -v docker >/dev/null 2>&1; then
        echo "${Colors.BRIGHT_CYAN}🐳 Using docker...${Colors.RESET}"
        docker build -t ${image_addr} ${buildArgsStr} . --no-cache && docker push ${image_addr}
      else
        echo "${Colors.RED}❌ Buildkit 和 Docker 工具不可用${Colors.RESET}"
        exit 1
      fi
    """.stripIndent().trim()
    
    script.sh """
      set -e
      ${buildCommand}
    """
    script.env.IMAGE_UPLOAD_SUCCESS = 'true'
  }

  // 辅助函数：解析各种格式的 build args
  def parseBuildArgs(buildArgs, String format = 'docker') {
      if (!buildArgs) {
        return ""
      }
      
      String result = ""
      
      if (buildArgs instanceof Map) {
        // Map 格式
        buildArgs.each { key, value ->
          if (format == 'docker') {
            result += "--build-arg ${key}=${value} "
          } else {
            result += "--opt build-arg:${key}=${value} "
          }
        }
      } else {
        // String 格式
        String argsStr = buildArgs.toString().trim()
        
        if (argsStr.contains('--build-arg')) {
            if (format == 'docker') {
              result = argsStr
            } else {
              // 转换为 buildctl 格式
              argsStr.split(/--build-arg[\s=]+/).findAll { it.trim() }.each { arg ->
                def cleaned = arg.replaceAll(/\\s+--.*$/, '').trim()
                if (cleaned) {
                  result += "--opt build-arg:${cleaned} "
                }
              }
            }
        } else {
          // 简化格式: "KEY1=value1 KEY2=value2"
          argsStr.split(/\\s+/).findAll { it.contains('=') }.each { arg ->
            if (format == 'docker') {
              result += "--build-arg ${arg} "
            } else {
              result += "--opt build-arg:${arg} "
            }
          }
        }
      }
      return result.trim()
  }
}
