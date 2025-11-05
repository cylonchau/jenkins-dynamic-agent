// src/io/jenkins/image/ImageTools.groovy
package io.jenkins.build

import io.jenkins.common.Colors

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
    def module_list = script.params.MODULES.split(',')
    def app_module = script.readJSON text: script.env.APP_MODULE
    def image_tag = script.env.CURRENT_COMMIT_ID

    switch (script.env.PROGRAMMING) {
      case 'frontend':
      case 'vue':
      case 'js':
        if (script.env.SHARED_MODULE.toBoolean() == true) {
          // 共享模块的镜像构建
          def first_mod = module_list[0]
          def subpath = app_module[first_mod]?.toString() ?: ''
          def path = "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/${subpath}"
          def image_addr = ""

          def suffix = script.env.JOB_SUFFIX
          if (suffix && suffix.trim()) {
              image_addr = "${script.env.DOCKER_REGISTRY}/${script.env.JOB_PREFIX}-${suffix.trim()}:${image_tag}"
          } else {
              image_addr = "${script.env.DOCKER_REGISTRY}/${script.env.JOB_PREFIX}:${image_tag}"
          }

          def projectName = "${script.env.JOB_PREFIX}"
          def dockerfileContent = """
            FROM nginx:1.22
            RUN sed -i 's/deb.debian.org/mirrors.aliyun.com/g' /etc/apt/sources.list && \
                  apt update && apt install wget && \
                  ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
                  rm -rf /var/cache/apt/*
            COPY dist/ /usr/share/nginx/html
          """.stripIndent()
          script.configFileProvider([script.configFile(fileId: "${script.env.REGISTRY_CREDNTIAL}", targetLocation: "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/.docker/config.json")]) {
            script.withEnv(["DOCKER_CONFIG=${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/.docker"]) {
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
            def subpath = app_module[mod]?.toString() ?: ''
            def path = "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/${subpath}"
            def image_addr = "${script.env.DOCKER_REGISTRY}/${script.env.JOB_PREFIX}-${mod}:${image_tag}"
            def projectName = "${script.env.JOB_PREFIX}-${mod}"
            def dockerfileContent = """
              FROM nginx:1.22
              RUN sed -i 's/deb.debian.org/mirrors.aliyun.com/g' /etc/apt/sources.list && \
                    apt update && apt install wget && \
                    ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
                    rm -rf /var/cache/apt/*
              COPY dist/ /usr/share/nginx/html
            """.stripIndent()

            script.configFileProvider([script.configFile(fileId: "${script.env.REGISTRY_CREDNTIAL}", targetLocation: "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/.docker/config.json")]) {
              script.withEnv(["DOCKER_CONFIG=${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/.docker"]) {
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
                  script.echo "${Colors.RED}错误：无法为模块 ${mod} 构建镜像 ${image_addr}，错误信息：${e}${Colors.RESET}"
                  script.error '❌ 镜像构建失败，请检查构建环境或 Dockerfile 配置'
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
          def subpath = app_module[first_mod]?.toString() ?: ''
          def path = "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/${subpath}"
          def image_addr = ""

          def suffix = script.env.JOB_SUFFIX
          if (suffix && suffix.trim()) {
              image_addr = "${script.env.DOCKER_REGISTRY}/${script.env.JOB_PREFIX}-${suffix.trim()}:${image_tag}"
          } else {
              image_addr = "${script.env.DOCKER_REGISTRY}/${script.env.JOB_PREFIX}:${image_tag}"
          }

          def projectName = "${script.env.JOB_PREFIX}"
          def dockerfileContent = """
            FROM debian:bookworm-slim:latest
            WORKDIR /app
            COPY ./target/release/${projectName} /app/${projectName}
            COPY ./crates /app/crates
            RUN echo "当前目录是:" && pwd && \\
                echo "检查 ${projectName} 是否存在:" && \\
                ls -l /app/*
            CMD ["./${projectName}", "./crates/${projectName}"]
          """.stripIndent()

          script.configFileProvider([script.configFile(fileId: "${script.env.REGISTRY_CREDNTIAL}", targetLocation: "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/.docker/config.json")]) {
            script.withEnv(["DOCKER_CONFIG=${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/.docker"]) {
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
            script.echo mod
            def subpath = app_module[mod]?.toString() ?: ''
            def path = "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/${subpath}"
            def image_addr = "${script.env.DOCKER_REGISTRY}/${script.env.JOB_PREFIX}-${mod}:${image_tag}"
            def projectName = "${script.env.JOB_PREFIX}-${mod}"
            if (script.env.NAME_ONLY.toBoolean() == true ) {
              projectName = "${mod}"
            }
            def dockerfileContent = """
              FROM debian:bookworm-slim:latest
              WORKDIR /app
              COPY ./target/release/${projectName} /app/${projectName}
              COPY ./crates /app/crates
              CMD ["./${projectName}", "./crates/${projectName}"]
            """.stripIndent()

            script.configFileProvider([script.configFile(fileId: "${script.env.REGISTRY_CREDNTIAL}", targetLocation: "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/.docker/config.json")]) {
              script.withEnv(["DOCKER_CONFIG=${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/.docker"]) {
                try {
                  script.dir(path) {
                    if (!script.fileExists('Dockerfile')) {
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
      default:
        if (script.env.SHARED_MODULE.toBoolean() == true) {
          // 共享模块的镜像构建
          def first_mod = module_list[0]
          def subpath = app_module[first_mod]?.toString() ?: ''
          def path = "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/${subpath}"
          def image_addr = ""

          def suffix = script.env.JOB_SUFFIX
          if (suffix && suffix.trim()) {
              image_addr = "${script.env.DOCKER_REGISTRY}/${script.env.JOB_PREFIX}-${suffix.trim()}:${image_tag}"
          } else {
              image_addr = "${script.env.DOCKER_REGISTRY}/${script.env.JOB_PREFIX}:${image_tag}"
          }

          // 根据不同编译环境的上下文执行
          // 文件操作必须为 job 的 ROOT_WORKSPACE 下，否则没权限
          script.configFileProvider([script.configFile(fileId: "${script.env.REGISTRY_CREDNTIAL}", targetLocation: "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/.docker/config.json")]) {
            script.withEnv(["DOCKER_CONFIG=${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/.docker"]) {
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
            def subpath = app_module[mod]?.toString() ?: ''
            def path = "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/${subpath}"
            def image_addr = "${script.env.DOCKER_REGISTRY}/${script.env.JOB_PREFIX}-${mod}:${image_tag}"

            script.configFileProvider([script.configFile(fileId: "${script.env.REGISTRY_CREDNTIAL}", targetLocation: "${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/.docker/config.json")]) {
              script.withEnv(["DOCKER_CONFIG=${script.env.ROOT_WORKSPACE}/${script.env.MAIN_PROJECT}/.docker"]) {
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
        echo "🔨 Using buildctl..."
        buildctl build \\
          --frontend dockerfile.v0 \\
          --local context=. \\
          --local dockerfile=. \\
          ${buildctlArgsStr} \\
          --output type=image,name=${image_addr},push=true
      elif command -v docker >/dev/null 2>&1; then
        echo "🐳 Using docker..."
        docker build -t ${image_addr} ${buildArgsStr} . --no-cache && docker push ${image_addr}
      else
        echo "❌ Buildkit 和 Docker 工具不可用"
        exit 1
      fi
    """.stripIndent().trim()
    
    script.sh """
      set -euxo pipefail
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
              argsStr.split(/--build-arg\s+/).findAll { it.trim() }.each { arg ->
                def cleaned = arg.replaceAll(/\s+--.*$/, '').trim()
                if (cleaned) {
                  result += "--opt build-arg:${cleaned} "
                }
              }
            }
        } else {
          // 简化格式: "KEY1=value1 KEY2=value2"
          argsStr.split(/\s+/).findAll { it.contains('=') }.each { arg ->
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