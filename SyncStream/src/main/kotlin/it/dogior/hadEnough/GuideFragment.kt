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
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.*

class GuideFragment : BottomSheetDialogFragment() {
    private val guideUrl = "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/master/guide/README_SyncStream.md"
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.guide_fragment, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val closeButton = view.findViewById<ImageButton>(R.id.close_button)
        val contentText = view.findViewById<TextView>(R.id.guide_content)
        val githubButton = view.findViewById<Button>(R.id.github_button)
        
        closeButton.setOnClickListener {
            dismiss()
        }
        
        githubButton.setOnClickListener {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://github.com/DieGon7771/ItaliaInStreaming/blob/master/guide/README_SyncStream.md")
            )
            startActivity(intent)
        }
        
        contentText.movementMethod = LinkMovementMethod.getInstance()
        
        // Carica la guida
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
    }
    
    private fun parseMarkdown(markdown: String): CharSequence {
        var html = markdown
            // Headers
            .replace(Regex("^# (.*?)$", setOf(RegexOption.MULTILINE)), "<h1>$1</h1>")
            .replace(Regex("^## (.*?)$", setOf(RegexOption.MULTILINE)), "<h2>$1</h2>")
            .replace(Regex("^### (.*?)$", setOf(RegexOption.MULTILINE)), "<h3>$1</h3>")
            
            // Grassetto
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")
            
            // Link
            .replace(Regex("\\[(.*?)\\]\\((.*?)\\)"), "<a href=\"$2\">$1</a>")
            
            // Elenchi
            .replace(Regex("^- (.*?)$", setOf(RegexOption.MULTILINE)), "• $1<br/>")
            .replace(Regex("^\\d+\\. (.*?)$", setOf(RegexOption.MULTILINE)), "$1<br/>")
            
            // Citazioni
            .replace(Regex("^> (.*?)$", setOf(RegexOption.MULTILINE)), "<i>$1</i><br/>")
            
            // Nuove righe
            .replace("\n", "<br/>")
        
        return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
    }
}
