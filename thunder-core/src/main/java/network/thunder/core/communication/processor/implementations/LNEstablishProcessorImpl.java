package network.thunder.core.communication.processor.implementations;

import network.thunder.core.communication.Message;
import network.thunder.core.communication.nio.P2PContext;
import network.thunder.core.communication.objects.lightning.establish.EstablishChannelMessageA;
import network.thunder.core.communication.objects.lightning.establish.EstablishChannelMessageB;
import network.thunder.core.communication.objects.lightning.establish.EstablishChannelMessageC;
import network.thunder.core.communication.objects.lightning.establish.EstablishChannelMessageD;
import network.thunder.core.communication.objects.messages.MessageExecutor;
import network.thunder.core.communication.objects.messages.interfaces.factories.LNEstablishFactory;
import network.thunder.core.communication.objects.messages.interfaces.message.lightningestablish.LNEstablish;
import network.thunder.core.communication.processor.interfaces.LightningChannelManagamentProcessor;
import network.thunder.core.database.objects.Channel;
import network.thunder.core.etc.Tools;
import network.thunder.core.mesh.Node;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.crypto.TransactionSignature;

/**
 * Created by matsjerratsch on 03/12/2015.
 */
public class LNEstablishProcessorImpl implements LightningChannelManagamentProcessor {
    Node node;
    MessageExecutor messageExecutor;
    LNEstablishFactory messageFactory;

    P2PContext context;
    Channel channel;

    int status = 0;

    @Override
    public void onInboundMessage (Message message) {
        if (message instanceof LNEstablish) {
            consumeMessage(message);
        } else {
            messageExecutor.sendMessageDownwards(message);
        }
    }

    @Override
    public void onOutboundMessage (Message message) {

    }

    @Override
    public void onLayerActive (MessageExecutor messageExecutor) {
        this.messageExecutor = messageExecutor;
        if (node.isServer) {
            sendEstablishMessageA();
        }
    }

    private void consumeMessage (Message message) {
        if (message instanceof EstablishChannelMessageA) {
            processMessageA(message);
        } else if (message instanceof EstablishChannelMessageB) {
            processMessageB(message);
        } else if (message instanceof EstablishChannelMessageC) {
            processMessageC(message);
        } else if (message instanceof EstablishChannelMessageD) {
            processMessageD(message);
        } else {
            throw new UnsupportedOperationException("Don't know this LNEstablish Message: " + message);
        }
    }

    private void processMessageA (Message message) {
        EstablishChannelMessageA m = (EstablishChannelMessageA) message;
        prepareNewChannel();

        channel.setInitialAmountServer(m.getClientAmount());
        channel.setAmountServer(m.getClientAmount());
        channel.setInitialAmountClient(m.getServerAmount());
        channel.setAmountClient(m.getServerAmount());

        channel.setKeyClient(ECKey.fromPublicOnly(m.getPubKey()));
        channel.setKeyClientA(ECKey.fromPublicOnly(m.getPubKeyFE()));
        channel.setAnchorSecretHashClient(m.getSecretHashFE());
        channel.setAnchorRevocationHashClient(m.getRevocationHash());

        sendEstablishMessageB();
    }

    private void processMessageB (Message message) {
        EstablishChannelMessageB m = (EstablishChannelMessageB) message;

        channel.setKeyClient(ECKey.fromPublicOnly(m.getPubKey()));
        channel.setKeyClientA(ECKey.fromPublicOnly(m.getPubKeyFE()));

        channel.setAnchorSecretHashClient(m.getSecretHashFE());
        channel.setAnchorRevocationHashClient(m.getRevocationHash());
        channel.setAmountClient(m.getServerAmount());
        channel.setInitialAmountClient(m.getServerAmount());

        channel.setAnchorTxHashClient(Sha256Hash.wrap(m.getAnchorHash()));

        sendEstablishMessageC();
    }

    private void processMessageC (Message message) {
        EstablishChannelMessageC m = (EstablishChannelMessageC) message;

        channel.setAnchorTxHashClient(Sha256Hash.wrap(m.getAnchorHash()));
        channel.setEscapeTxSig(TransactionSignature.decodeFromBitcoin(m.getSignatureE(), true));
        channel.setFastEscapeTxSig(TransactionSignature.decodeFromBitcoin(m.getSignatureFE(), true));

        if (!channel.verifyEscapeSignatures()) {
            throw new RuntimeException("Signature does not match..");
        }

        sendEstablishMessageD();
    }

    private void processMessageD (Message message) {
        EstablishChannelMessageD m = (EstablishChannelMessageD) message;

        channel.setEscapeTxSig(TransactionSignature.decodeFromBitcoin(m.getSignatureE(), true));
        channel.setFastEscapeTxSig(TransactionSignature.decodeFromBitcoin(m.getSignatureFE(), true));

        if (!channel.verifyEscapeSignatures()) {
            throw new RuntimeException("Signature does not match..");
        }
        //TODO: Everything needed has been exchanged. We can now open the channel / wait to see the other channel on the blockchain.
        //          We need a WatcherClass on the BlockChain for that, to wait till the anchors are sufficiently deep in the blockchain.
    }

    private void prepareNewChannel () {
        channel = new Channel();
        channel.setInitialAmountServer(context.getAmountForNewChannel());
        channel.setAmountServer(context.getAmountForNewChannel());

        channel.setInitialAmountClient(context.getAmountForNewChannel());
        channel.setAmountClient(context.getAmountForNewChannel());

        //TODO: Change base key selection method (maybe completely random?)
        channel.setKeyServer(ECKey.fromPrivate(Tools.hashSha(node.context.nodeKey.getPrivKeyBytes(), 2)));
        channel.setKeyServerA(ECKey.fromPrivate(Tools.hashSha(node.context.nodeKey.getPrivKeyBytes(), 4)));
        channel.setMasterPrivateKeyServer(Tools.hashSha(node.context.nodeKey.getPrivKeyBytes(), 6));
        byte[] secretFE = Tools.hashSecret(Tools.hashSha(node.context.nodeKey.getPrivKeyBytes(), 8));
        byte[] revocation = Tools.hashSecret(Tools.hashSha(node.context.nodeKey.getPrivKeyBytes(), 10));
        channel.setAnchorSecretServer(secretFE);
        channel.setAnchorSecretHashServer(Tools.hashSecret(secretFE));
        channel.setAnchorRevocationServer(revocation);
        channel.setAnchorRevocationHashServer(Tools.hashSecret(revocation));
        channel.setServerChainDepth(1000);
        channel.setServerChainChild(0);
        channel.setIsReady(false);

        status = 1;

    }

    private void sendEstablishMessageA () {
        prepareNewChannel();

        Message message = messageFactory.getEstablishMessageA(channel);
        messageExecutor.sendMessageUpwards(message);

        status = 2;
    }

    private void sendEstablishMessageB () {

        Transaction anchor = channel.getAnchorTransactionServer(context.wallet, context.lockedOutputs);

        Message message = messageFactory.getEstablishMessageB(channel, anchor);
        messageExecutor.sendMessageUpwards(message);

        status = 3;
    }

    private void sendEstablishMessageC () {

        Transaction anchor = channel.getAnchorTransactionServer(context.wallet, context.lockedOutputs);

        Transaction escape = channel.getEscapeTransactionClient();
        Transaction fastEscape = channel.getFastEscapeTransactionClient();

        TransactionSignature escapeSig = Tools.getSignature(escape, 0, channel.getScriptAnchorOutputClient().getProgram(), channel.getKeyServerA());
        TransactionSignature fastEscapeSig = Tools.getSignature(fastEscape, 0, channel.getScriptAnchorOutputClient().getProgram(), channel
                .getKeyServerA());

        Message message = messageFactory.getEstablishMessageC(anchor, escapeSig, fastEscapeSig);
        messageExecutor.sendMessageUpwards(message);

        status = 4;
    }

    private void sendEstablishMessageD () {

        Transaction escape = channel.getEscapeTransactionClient();
        Transaction fastEscape = channel.getFastEscapeTransactionClient();

        TransactionSignature escapeSig = Tools.getSignature(escape, 0, channel.getScriptAnchorOutputClient().getProgram(), channel.getKeyServerA());
        TransactionSignature fastEscapeSig = Tools.getSignature(fastEscape, 0, channel.getScriptAnchorOutputClient().getProgram(), channel
                .getKeyServerA());

        Message message = messageFactory.getEstablishMessageD(escapeSig, fastEscapeSig);
        messageExecutor.sendMessageUpwards(message);

        status = 5;
    }

}
