import com.dtolabs.rundeck.plugins.notification.NotificationPlugin
import groovy.text.SimpleTemplateEngine
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * PagerDuty Notes Notification Plugin
 * 
 * Sends Rundeck job notifications as notes to PagerDuty incidents.
 */

def generateNoteContent = { String trigger, Map execution, Map config ->
    def statusMap = [
        start: "STARTED",
        success: "SUCCEEDED", 
        failure: "FAILED"
    ]
    def status = statusMap[trigger] ?: trigger.toUpperCase()
    
    def jobName = execution.job?.name ?: "Unknown Job"
    def jobGroup = execution.job?.group ? "${execution.job.group}/" : ""
    def jobFullName = "${jobGroup}${jobName}"
    def jobDescription = execution.job?.description ?: ""
    def jobHref = execution.href ?: "No URL available"
    def user = execution.user ?: "Unknown User"
    def project = execution.project ?: "Unknown Project"
    def executionId = execution.id ?: "Unknown ID"
    
    def dateStarted = execution.dateStarted ? new Date(execution.dateStarted.time).toString() : "Unknown"
    def dateEnded = execution.dateEnded ? new Date(execution.dateEnded.time).toString() : ""
    
    def noteLines = []
    
    def statusText = ""
    if (trigger == 'start') {
        statusText = "STARTED"
    } else if (trigger == 'success') {
        statusText = "SUCCEEDED"
    } else if (trigger == 'failure') {
        statusText = "FAILED"
    }
    
    noteLines << "Job \"${jobFullName}\" has ${statusText}"
    
    if (jobDescription && trigger == 'start') {
        noteLines << "Job Description: \"${jobDescription}\""
    }
    
    noteLines << ""
    
    def timestamp = ""
    def executionStatusText = ""
    if (trigger == 'start') {
        timestamp = dateStarted
        executionStatusText = "started"
    } else if (trigger == 'success') {
        timestamp = dateEnded ?: dateStarted
        executionStatusText = "succeeded"
    } else if (trigger == 'failure') {
        timestamp = dateEnded ?: dateStarted
        executionStatusText = "failed"
    }
    
    noteLines << "Execution ${executionStatusText} at ${timestamp}"
    
    def context = execution.context
    def options = context?.option
    if (options && options.size() > 0 && trigger == 'start') {
        noteLines << ""
        noteLines << "USER OPTIONS:"
        options.each { key, value ->
            def secureOptions = context?.secureOption
            if (!secureOptions?.containsKey(key)) {
                noteLines << "${key}: ${value}"
            }
        }
    }
    
    if (execution.abortedby) {
        noteLines << ""
        noteLines << "Job was aborted by: ${execution.abortedby}"
    }
    
    noteLines << ""
    noteLines << "View execution: ${jobHref}"
    
    return noteLines.join("\n")
}

def escapeJsonContent = { String content ->
    if (!content) return ""
    return content
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}

def addNoteToPagerDutyIncident = { String incidentId, String noteContent, String apiToken, String requesterEmail ->
    println "[PagerDutyNotes] Adding note to incident: ${incidentId}"
    println "[PagerDutyNotes] Note content: ${noteContent}"
    
    def urlString = "https://api.pagerduty.com/incidents/${incidentId}/notes"
    println "[PagerDutyNotes] API URL: ${urlString}"
    
    def url = new URL(urlString)
    def connection = url.openConnection() as HttpURLConnection
    
    try {
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", "Token token=${apiToken}")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/vnd.pagerduty+json;version=2")
        connection.setRequestProperty("From", requesterEmail)
        connection.doOutput = true
        
        def escapedContent = escapeJsonContent(noteContent)
        def jsonPayload = """{"note": {"content": "${escapedContent}"}}"""
        println "[PagerDutyNotes] JSON Payload: ${jsonPayload}"
        
        connection.outputStream.withWriter("UTF-8") { writer ->
            writer.write(jsonPayload)
        }
        
        def responseCode = connection.responseCode
        println "[PagerDutyNotes] Response Code: ${responseCode}"
        
        if (responseCode >= 200 && responseCode < 300) {
            println "[PagerDutyNotes] Successfully added note to PagerDuty incident ${incidentId}"
            
            def response = connection.inputStream.text
            println "[PagerDutyNotes] Response: ${response}"
            
            return true
        } else {
            def errorMessage = "Failed to add note to PagerDuty incident ${incidentId}. Response Code: ${responseCode}"
            
            if (connection.errorStream) {
                def errorResponse = connection.errorStream.text
                errorMessage += "\\nError Response: ${errorResponse}"
            }
            
            println "[PagerDutyNotes] ERROR: ${errorMessage}"
            return false
        }
        
    } catch (Exception e) {
        println "[PagerDutyNotes] Exception: ${e.message}"
        e.printStackTrace()
        return false
    } finally {
        connection.disconnect()
    }
}

rundeckPlugin(NotificationPlugin) {

    title = "PagerDuty Notes"
    
    description = "Sends Rundeck Notifications as notes to PagerDuty Incidents"

    configuration {
        
        pagerdutyApiToken(
            title: "PagerDuty API Token",
            type: "String",
            required: true,
            renderingOptions: [
                instance: [
                    "STORAGE_PATH_ROOT": "keys",
                    "valueConversion": "STORAGE_PATH_AUTOMATIC_READ"
                ]
            ],
            description: "PagerDuty REST API Key (e.g., u+xxxx). This is the token used for authentication with the PagerDuty API."
        )
        
        pagerdutyEmail(
            title: "PagerDuty Requester Email",
            type: "String", 
            required: true,
            description: "The email address of the PagerDuty user adding the note (e.g., tanmay.bhat@hunters.ai). This email must be associated with a PagerDuty user account."
        )
    }

    def handleTrigger = { String trigger, Map execution, Map config ->
        try {
            println "[PagerDutyNotes] ========== NOTIFICATION TRIGGERED =========="
            println "[PagerDutyNotes] Trigger: ${trigger}"
            println "[PagerDutyNotes] ExecutionData keys: ${execution.keySet()}"
            println "[PagerDutyNotes] Config: ${config}"
            
            def context = execution.context
            println "[PagerDutyNotes] Context: ${context}"
            
            def options = context?.option
            println "[PagerDutyNotes] Options: ${options}"
            
            // Get incident ID from job option
            def incidentId = options?.incident_id
            println "[PagerDutyNotes] Incident ID from options: '${incidentId}'"
            
            if (!incidentId) {
                println "[PagerDutyNotes] ERROR: No incident_id found in job options"
                System.err.println("PagerDuty Incident ID is required. Please set 'incident_id' as a job option.")
                return false
            }
            
            if (!config.pagerdutyApiToken) {
                println "[PagerDutyNotes] ERROR: PagerDuty API Token is not configured"
                System.err.println("PagerDuty API Token is not configured.")
                return false
            }
            
            if (!config.pagerdutyEmail) {
                println "[PagerDutyNotes] ERROR: PagerDuty Email is not configured"
                System.err.println("PagerDuty Email is not configured.")
                return false
            }
            
            def noteContent = generateNoteContent(trigger, execution, config)
            println "[PagerDutyNotes] Generated note content: ${noteContent}"
            
            def result = addNoteToPagerDutyIncident(incidentId, noteContent, config.pagerdutyApiToken, config.pagerdutyEmail)
            println "[PagerDutyNotes] Result: ${result}"
            
            return result
            
        } catch (Exception e) {
            println "[PagerDutyNotes] ERROR: ${e.message}"
            e.printStackTrace()
            System.err.println("Error sending PagerDuty notification: ${e.message}")
            return false
        }
    }

    onsuccess(handleTrigger.curry('success'))
    onfailure(handleTrigger.curry('failure'))
    onstart(handleTrigger.curry('start'))
}
