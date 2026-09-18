package it.dogior.hadEnough.watchparty

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.widget.ImageButton
import android.widget.ImageView

/**
 * Sostituto di com.google.android.material.floatingactionbutton.FloatingActionButton.
 *
 * Il loader dei plugin CloudStream sembra escludere le librerie AndroidX/
 * Material "comuni" dal pacchetto finale del plugin, assumendo che l'host
 * le fornisca già — vero per CloudStream (che è un'app Material), falso
 * per Nuvio Enhanced (Compose/Material3, non porta con sé la libreria
 * classica com.google.android.material) → NoClassDefFoundError a runtime
 * per qualunque plugin la usi, indipendentemente da questo bridge.
 *
 * Alias di tipo: nel resto del file basta cambiare il tipo dichiarato
 * (FloatingActionButton -> FabButton), la API usata (setImageDrawable,
 * setImageResource, setOnClickListener, alpha) è la stessa perché FabButton
 * è comunque un ImageView/View standard.
 */
typealias FabButton = ImageButton

/** Crea un ImageButton circolare che approssima visivamente una FAB, senza dipendenze esterne. */
fun createFabButton(activity: Activity, sizeDp: Int = 56, backgroundColor: Int = 0xFF2E7DFF.toInt()): FabButton {
    val density = activity.resources.displayMetrics.density
    val sizePx = (sizeDp * density).toInt()

    val circle = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(backgroundColor)
    }
    val foreground: Drawable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), circle, circle)
    } else {
        circle
    }

    return ImageButton(activity).apply {
        setBackground(foreground)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        minimumWidth = sizePx
        minimumHeight = sizePx
        val pad = (sizePx * 0.28f).toInt()
        setPadding(pad, pad, pad, pad)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elevation = 8f * density
        }
        // niente tint di sfondo di sistema: il cerchio colorato è già lo sfondo
        setColorFilter(android.graphics.Color.WHITE)
    }
}
