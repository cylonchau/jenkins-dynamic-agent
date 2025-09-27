# Jenkins Dynamic Agent Library (jenkinslib)

A powerful and flexible Jenkins Shared Library designed to automate CI/CD pipelines for multi-language projects (Java, Rust, GO, Python, Vue/Frontend) with support for both Virtual Machine (VM) and Kubernetes (K8s) deployments.

## Key Features

- **Intelligent Agent Manager**: Automatic agent recommendation (`docker`, `k8s`, `node`) based on project language and target platform.
- **Smart Build Skipping**: Integrated with Harbor Registry to check for existing images via commit tags, avoiding redundant builds.
- **Flexible Deployment Strategies**: Unified support for both VM (via SSH/SCP) and Kubernetes (kubectl), with dynamic command evaluation for VM tasks.
- **Multi-Language Build Pipeline**: Standardized build flows for Java (Maven), Rust (Cargo), Go, Python and Frontend (Node/NPM) projects.
- **Release Bypassing**: Ability to download pre-compiled binaries directly from GitHub Releases to bypass the build stage.


## Required Jenkins Plugins

To use this library, ensure the following plugins are installed and configured:

- [Kubernetes](https://plugins.jenkins.io/kubernetes/)
- [Docker Pipeline](https://plugins.jenkins.io/docker-workflow/)
- [Pipeline Utility Steps](https://plugins.jenkins.io/pipeline-utility-steps/)
- [Active Choices](https://plugins.jenkins.io/uno-choice/)
- [AnsiColor](https://plugins.jenkins.io/ansicolor/)

## Configuration

The `modules.json` file is the heart of the library. It contains global settings and fine-grained module configurations.

## Usage Example

In your `Jenkinsfile` (or `main.groovy`):

```groovy
@Library('jenkins_lib_name@branch') _

pipeline {
    agent any
    stages {
        stage('Example Build') {
            steps {
                script {
                    // Initialization and build logic handled by the library
                }
            }
        }
    }
}
```

## Script Approval Requirements

Certain methods used by this library may require script approval in Jenkins (not must):

```
method hudson.model.Run getDescription
method hudson.model.Run getPreviousBuild
method java.lang.Throwable printStackTrace
method net.sf.json.JSONArray join java.lang.String
method org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper getRawBuild
staticMethod groovy.json.JsonOutput toJson java.lang.Object
staticMethod org.codehaus.groovy.runtime.DefaultGroovyMethods inspect java.lang.Object
```

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.