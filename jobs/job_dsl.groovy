import hudson.util.Secret
import jenkins.model.Jenkins
import net.gleske.jervis.exceptions.SecurityException
import net.gleske.jervis.lang.lifecycleGenerator
import net.gleske.jervis.remotes.GitHub

def git_service = new GitHub()
//generate Jenkins jobs
def generate_project_for(def git_service, String JERVIS_BRANCH) {
    //def JERVIS_BRANCH = it
    def folder_listing = git_service.getFolderListing(project, '/', JERVIS_BRANCH)
	def generator = new lifecycleGenerator()
    String jervis_yaml
    if('.jervis.yml' in folder_listing) {
        jervis_yaml = git_service.getFile(project, '.jervis.yml', JERVIS_BRANCH)
    }
    else if('.travis.yml' in folder_listing) {
        jervis_yaml = git_service.getFile(project, '.travis.yml', JERVIS_BRANCH)
    }
    else {
        //skip creating the job for this branch
        println "Skipping branch: ${JERVIS_BRANCH}"
        return
    }
	
    generator.loadPlatformsString(readFileFromWorkspace('src/main/resources/platforms.json').toString())
    generator.preloadYamlString(jervis_yaml)
    //could optionally read lifecycles and toolchains files by OS
    generator.loadLifecyclesString(readFileFromWorkspace('src/main/resources/lifecycles.json').toString())
    generator.loadToolchainsString(readFileFromWorkspace('src/main/resources/toolchains.json').toString())
    generator.loadYamlString(jervis_yaml)
    String project_folder = "${project}".split('/')[0]
    
    generator.folder_listing = folder_listing
    if(!generator.isGenerateBranch(JERVIS_BRANCH)) {
        //the job should not be generated for this branch
        //based on the branches section of .jervis.yml
        println "Skipping branch: ${JERVIS_BRANCH}"
        return
    }
    //chooses job type based on Jervis YAML
    def jervis_jobType
    if(generator.isMatrixBuild()) {
        jervis_jobType = { String name, Closure closure -> matrixJob(name, closure) }
    }
    else {
        jervis_jobType = { String name, Closure closure -> freeStyleJob(name, closure) }
    }
    println "Generating branch: ${JERVIS_BRANCH}"
    //the generated Job DSL enclosure depends on the job type
    jervis_jobType("${project_folder}/" + "${project_name}-${JERVIS_BRANCH}".replaceAll('/','-')) {
        displayName("${project_name} (${JERVIS_BRANCH} branch)")
        label(generator.getLabels())
        if(generator.isMatrixBuild()) {
            properties {
                groovyLabelAssignmentProperty {
                    secureGroovyScript {
                        script("""return currentJob.getClass().getSimpleName().equals('MatrixProject') ? 'master' : '${generator.getLabels()}'""")
                        sandbox(false)
                    }
                }
            }
        }
        //configure encrypted properties
        if(generator.plainlist.size() > 0) {
            configure { project ->
                project / 'buildWrappers' / 'com.michelin.cio.hudson.plugins.maskpasswords.MaskPasswordsBuildWrapper' / 'varPasswordPairs'() {
                    generator.plainlist.each { pair ->
                        'varPasswordPair'(var: pair['key'], password: Secret.fromString(pair['secret']).getEncryptedValue())
                    }
                }
            }
        }
        scm {
            git {
                remote {
                    url(git_service.getCloneUrl() + "${project}.git")
					credentials(generator.getObjectValue(generator.jervis_yaml, 'jenkins.credential_id', ''))
                }
                branch("refs/heads/${JERVIS_BRANCH}")
                //configure git web browser based on the type of remote
                switch(git_service) {
                    case GitHub:
                        configure { gitHub ->
                            gitHub / browser(class: 'hudson.plugins.git.browser.GithubWeb') {
                                url(git_service.getWebUrl() + "${project}")
                            }
                        }
                }
            }
        }
        steps {
            shell([
                readFileFromWorkspace('scripts/shellScript.sh'),
                "echo \"Hello World!!\""
                ].join('\n'))
        }
        //if a matrix build then generate matrix bits
        if(generator.isMatrixBuild()) {
            axes {
                generator.yaml_matrix_axes.each {
                    text(it, generator.matrixGetAxisValue(it).split())
                }
            }
            combinationFilter(generator.matrixExcludeFilter())
        }
        publishers {
			extendedEmail {
				recipientList(generator.getObjectValue(generator.jervis_yaml, 'jenkins.email.recepients', '') as String[])
				defaultSubject("AARive-Deployment")
				attachBuildLog(true)
				triggers {
					failure {
						sendTo {
							recipientList()
						}
					}
					success {
						sendTo {
							recipientList()
						}
					}
				}
			}
        }
    }
}

if("${project}".size() > 0 && "${project}".split('/').length == 2) {
    println 'Generating jobs for ' + git_service.toString() + " project ${project}."

    project_folder = "${project}".split('/')[0]
    project_name = "${project}".split('/')[1]

    if(!Jenkins.instance.getItem(project_folder)) {
        println "Creating folder ${project_folder}"
        folder(project_folder)
    }

    println "Creating project ${project}"
    listView("${project}") {
        description(git_service.toString() + ' Project ' + git_service.getWebUrl() + "${project}")
        filterBuildQueue()
        filterExecutors()
        jobs {
            regex('^' + "${project_name}".replaceAll('/','-') + '.*')
        }
        columns {
            status()
            weather()
            name()
            lastSuccess()
            lastFailure()
            lastDuration()
            buildButton()
        }
    }
    //generate projects for one or more branches
    if(branch != null && branch.size() > 0) {
        generate_project_for(git_service, branch)
    }
    else {
        git_service.branches("${project}").each { branch ->
            generate_project_for(git_service, branch)
        }
    }
}
else {
    throw new ScriptException('Job parameter "project" must be specified correctly!  It\'s value is a GitHub project in the form of ${namespace}/${project}.  For example, the value for the jenkinsci organization jenkins project would be set as "jenkinsci/jenkins".')
}