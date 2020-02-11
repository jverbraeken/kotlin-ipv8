package nl.tudelft.ipv8.android.demo.ui.blocks

import android.view.View
import androidx.core.view.isVisible
import com.mattskala.itemadapter.ItemLayoutRenderer
import kotlinx.android.synthetic.main.item_block.view.*
import nl.tudelft.ipv8.android.demo.R
import nl.tudelft.ipv8.util.toHex

class BlockItemRenderer(
    private val onExpandClick: (BlockItem) -> Unit
) : ItemLayoutRenderer<BlockItem, View>(BlockItem::class.java) {
    override fun bindView(item: BlockItem, view: View) = with(view) {
        val block = item.block
        txtPublicKey.text = block.publicKey.toHex()
        txtLinkPublicKey.text = block.linkPublicKey.toHex()
        txtSequenceNumber.text = block.sequenceNumber.toString()
        txtLinkSequenceNumber.text = block.linkSequenceNumber.toString()

        txtExpandedPublicKey.text = block.publicKey.toHex()
        txtExpandedLinkPublicKey.text = block.linkPublicKey.toHex()
        txtPrevHash.text = block.previousHash.toHex()
        txtType.text = block.type
        txtTransaction.text = block.transaction["message"]?.toString()
        txtExpandedTransaction.text = block.transaction.toString()
        txtTimestamp.text = block.timestamp.toString()
        txtInsertTime.text = block.insertTime?.toString()
        txtBlockHash.text = block.calculateHash().toHex()
        txtSignature.text = block.signature.toHex()

        header.setOnClickListener {
            onExpandClick(item)
        }

        expandedItem.isVisible = item.isExpanded
        btnExpand.scaleY = if (item.isExpanded) -1f else 1f
    }

    override fun getLayoutResourceId(): Int {
        return R.layout.item_block
    }
}
