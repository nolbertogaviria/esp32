package com.twister.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.twister.bridge.db.TwisterDbHelper
import com.twister.bridge.db.WhatsAppNotif

class HistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateView: View
    private lateinit var adapter: HistoryAdapter
    private lateinit var dbHelper: TwisterDbHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        recyclerView = findViewById(R.id.historyRecyclerView)
        emptyStateView = findViewById(R.id.emptyStateView)
        dbHelper = TwisterDbHelper.getInstance(this)

        setupRecyclerView()
        loadData()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_CLEAR_ALL, 0, "Eliminar todo").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_CLEAR_ALL) {
            showClearAllConfirmationDialog()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupRecyclerView() {
        adapter = HistoryAdapter(
            onCopyClick = { item -> copyToClipboard(item) },
            onShareClick = { item -> shareNotification(item) },
            onDeleteClick = { item -> deleteNotification(item) }
        )
        recyclerView.adapter = adapter
    }

    private fun loadData() {
        Thread {
            val list = dbHelper.getAllNotifications()
            runOnUiThread {
                adapter.submitList(list) {
                    if (list.isEmpty()) {
                        emptyStateView.visibility = View.VISIBLE
                        recyclerView.visibility = View.GONE
                    } else {
                        emptyStateView.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE
                    }
                }
            }
        }.start()
    }

    private fun copyToClipboard(item: WhatsAppNotif) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("whatsapp_message", "${item.sender}: ${item.message}")
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Mensaje copiado", Toast.LENGTH_SHORT).show()
    }

    private fun shareNotification(item: WhatsAppNotif) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "${item.sender}: ${item.message}")
        }
        startActivity(Intent.createChooser(shareIntent, "Compartir mensaje"))
    }

    private fun deleteNotification(item: WhatsAppNotif) {
        Thread {
            dbHelper.deleteNotification(item.id)
            loadData()
        }.start()
        Toast.makeText(this, "Mensaje eliminado", Toast.LENGTH_SHORT).show()
    }

    private fun showClearAllConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("¿Eliminar todo el historial?")
            .setMessage("Esta acción borrará de forma permanente todos los mensajes guardados en el historial.")
            .setPositiveButton("Eliminar") { _, _ ->
                Thread {
                    dbHelper.deleteAllNotifications()
                    loadData()
                }.start()
                Toast.makeText(this, "Historial vaciado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    companion object {
        private const val MENU_CLEAR_ALL = 1
    }
}
