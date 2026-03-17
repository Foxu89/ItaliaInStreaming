package it.dogior.hadEnough

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.text.HtmlCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.*

class GuideFragment(private val plugin: Plugin) : BottomSheetDialogFragment() {
    private val guideUrl = "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/guide/README_SyncStream.md"
    
    private fun <T : View> View.findView(name: String): T {
        val id = plugin.resources!!.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return this.findViewById(id)
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = plugin.resources!!.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        val layout = plugin.resources!!.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return try {
            getLayout("guide_fragment", inflater, container)
        } catch (e: Exception) {
            null
        }
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        try {
            val closeButton = view.findView<ImageButton>("close_button")
            val contentText = view.findView<TextView>("guide_content")
            val githubButton = view.findView<Button>("github_button")
            
            closeButton.setOnClickListener { dismiss() }
            
            githubButton.setOnClickListener {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/DieGon7771/ItaliaInStreaming/blob/master/guide/README_SyncStream.md")
                )
                startActivity(intent)
            }
            
            contentText.movementMethod = LinkMovementMethod.getInstance()
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val response = app.get(guideUrl)
                    val markdown = response.text
                    
                    withContext(Dispatchers.Main) {
                        contentText.text = parseMarkdown(markdown)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        contentText.text = "Errore nel caricare la guida:\n${e.message}"
                    }
                }
            }
        } catch (e: Exception) {
            // Ignora
        }
    }
    
    private fun parseMarkdown(markdown: String): CharSequence {
        var html = markdown
            // Headers con margini ridotti
            .replace(Regex("^# (.*?)$", setOf(RegexOption.MULTILINE)), "<h1 style='margin:8px 0 4px 0;'>$1</h1>")
            .replace(Regex("^## (.*?)$", setOf(RegexOption.MULTILINE)), "<h2 style='margin:6px 0 3px 0;'>$1</h2>")
            .replace(Regex("^### (.*?)$", setOf(RegexOption.MULTILINE)), "<h3 style='margin:4px 0 2px 0;'>$1</h3>")
            
            // Grassetto e corsivo
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")
            .replace(Regex("\\*(.*?)\\*"), "<i>$1</i>")
            
            // Link
            .replace(Regex("\\[(.*?)\\]\\((.*?)\\)"), "<a href=\"$2\">$1</a>")
            
            // Elenchi
            .replace(Regex("^- (.*?)$", setOf(RegexOption.MULTILINE)), "• $1<br/>")
            .replace(Regex("^\\d+\\. (.*?)$", setOf(RegexOption.MULTILINE)), "$1<br/>")
            
            // Citazioni
            .replace(Regex("^> (.*?)$", setOf(RegexOption.MULTILINE)), "<i>$1</i><br/>")
            
            // Linee divisorie
            .replace(Regex("^---$", setOf(RegexOption.MULTILINE)), "<hr style='margin:8px 0;'/>")
            
            // Paragrafi (doppio a capo)
            .replace(Regex("\\n\\n+"), "<br/><br/>")
            
            // Singolo a capo diventa spazio, non nuova riga
            .replace("\n", " ")
        
        return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
    }
}
