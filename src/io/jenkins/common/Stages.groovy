// src/io/jenkins/common/Stages.groovy
package io.jenkins.common
import io.jenkins.common.Colors
/**
 * Stages
 * 
 * 用法：
 *   import io.jenkins.common.Stages.*
 */
class Stages implements Serializable {
  private transient script

  private Stages(script) {
    this.script = script
  }

  static Stages getInstance(script) {
    return new Stages(script)
  }
  
  def pullCode(String git_repo, String git_credentials, String branch, String commit = null) {
      def selectedBranch = branch
      def commitId = null
      script.env.IS_FIRST_BUILD = (script.currentBuild.number == 1) ? 'true' : 'false'
      def fallbackBranches = script.env.FALLBACK_BRANCHES ? script.env.FALLBACK_BRANCHES.split(',').collect { it.trim() } : ['master', 'test']

      try {
          script.echo "${Colors.CYAN}准备拉取代码: ${git_repo}, 分支: ${selectedBranch}, commit: ${commit ?: 'latest'}${Colors.RESET}"

          // 先拉分支
          script.checkout([
              $class: 'GitSCM',
              branches: [[name: "*/${selectedBranch}"]],
              userRemoteConfigs: [[credentialsId: "${git_credentials}", url: "${git_repo}"]],
              extensions: [[$class: 'CloneOption', timeout: 5], [$class: 'WipeWorkspace']]
          ])

          // 如果指定了 commitId，就切到 commit
          if (commit) {
              script.sh "git reset --hard ${commit}"
              commitId = commit
              script.echo "${Colors.GREEN}已检出指定 commit: ${commitId}${Colors.RESET}"
          } else {
              commitId = script.sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
              script.echo "${Colors.GREEN}已检出分支 ${selectedBranch} 最新 commit: ${commitId}${Colors.RESET}"
          }

          if (script.env.IS_FIRST_BUILD == 'true') {
              script.env.USED_FALLBACK_BRANCH = 'true'
              script.echo "${Colors.BG_CYAN}第一次执行（构建 #${script.currentBuild.number}），终止流水线${Colors.RESET}"
              script.currentBuild.result = 'ABORTED'
              script.error "终止流水线: 第一次构建仅用于初始化参数"
          }

          return commitId
      } catch (Exception e) {
          script.echo "${Colors.RED}拉取分支 ${selectedBranch} 失败: ${e}${Colors.RESET}"
      }

      // fallback 分支逻辑
      for (fallbackBranch in fallbackBranches) {
          fallbackBranch = fallbackBranch.replaceAll('"', '')
          if (fallbackBranch != selectedBranch) {
              try {
                  script.echo "${Colors.YELLOW}尝试备用分支: ${fallbackBranch}${Colors.RESET}"
                  script.checkout([
                      $class: 'GitSCM',
                      branches: [[name: "*/${fallbackBranch}"]],
                      userRemoteConfigs: [[credentialsId: "${git_credentials}", url: "${git_repo}"]],
                      extensions: [[$class: 'CloneOption', timeout: 5], [$class: 'WipeWorkspace']]
                  ])
                  commitId = script.sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                  script.echo "${Colors.GREEN}成功通过备用分支 ${fallbackBranch} 获取了参数列表，根据要求终止流水线${Colors.RESET}"
                  script.env.USED_FALLBACK_BRANCH = 'true'
                  script.currentBuild.result = 'ABORTED'
                  script.error "终止流水线: 已通过备用分支更新参数"
                  return commitId
              } catch (Exception ex) {
                  script.echo "${Colors.RED}拉取备用分支 ${fallbackBranch} 失败: ${ex.message}${Colors.RESET}"
              }
          }
      }

      script.error "无法拉取任何分支（尝试了 ${selectedBranch} 和 ${fallbackBranches}）。请检查仓库 ${git_repo} 和凭据 ${git_credentials}。"
  }
}