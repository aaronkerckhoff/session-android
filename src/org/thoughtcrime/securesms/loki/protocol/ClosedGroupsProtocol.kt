package org.thoughtcrime.securesms.loki.protocol

import android.content.Context
import android.util.Log
import nl.komponents.kovenant.Promise
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.database.Address
import org.thoughtcrime.securesms.database.DatabaseFactory
import org.thoughtcrime.securesms.loki.utilities.recipient
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.sms.OutgoingTextMessage
import org.thoughtcrime.securesms.util.GroupUtil
import org.thoughtcrime.securesms.util.Hex
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.whispersystems.libsignal.ecc.Curve
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import org.whispersystems.signalservice.loki.protocol.closedgroups.ClosedGroupRatchet
import org.whispersystems.signalservice.loki.protocol.closedgroups.ClosedGroupSenderKey
import org.whispersystems.signalservice.loki.protocol.closedgroups.SharedSenderKeysImplementation
import org.whispersystems.signalservice.loki.utilities.hexEncodedPrivateKey
import org.whispersystems.signalservice.loki.utilities.hexEncodedPublicKey
import org.whispersystems.signalservice.loki.utilities.toHexString
import java.util.*

object ClosedGroupsProtocol {

    public fun createClosedGroup(context: Context, name: String, members: Collection<String>): Promise<Unit, Exception> {
        // Prepare
        val userPublicKey = TextSecurePreferences.getLocalNumber(context)
        // Generate a key pair for the group
        val groupKeyPair = Curve.generateKeyPair()
        val groupPublicKey = groupKeyPair.hexEncodedPublicKey // Includes the "05" prefix
        val membersAsData = members.map { Hex.fromStringCondensed(it) }
        // Create ratchets for all members
        val senderKeys: List<ClosedGroupSenderKey> = members.map { publicKey ->
            val ratchet = SharedSenderKeysImplementation.shared.generateRatchet(groupPublicKey, publicKey)
            ClosedGroupSenderKey(Hex.fromStringCondensed(ratchet.chainKey), ratchet.keyIndex, Hex.fromStringCondensed(publicKey))
        }
        // Create the group
        val groupID = GroupUtil.getEncodedId(Hex.fromStringCondensed(groupPublicKey), false)
        val admins = setOf( Address.fromSerialized(userPublicKey) )
        DatabaseFactory.getGroupDatabase(context).create(groupID, name, LinkedList<Address>(members.map { Address.fromSerialized(it) }),
            null, null, LinkedList<Address>(admins))
        DatabaseFactory.getRecipientDatabase(context).setProfileSharing(Recipient.from(context, Address.fromSerialized(groupID), false), true)
        // Establish sessions if needed
        establishSessionsWithMembersIfNeeded(context, members)
        // Send a closed group update message to all members using established channels
        val adminsAsData = admins.map { Hex.fromStringCondensed(it.serialize()) }
        val closedGroupUpdateKind = ClosedGroupUpdateMessageSendJob.Kind.New(Hex.fromStringCondensed(groupPublicKey), name, groupKeyPair.privateKey.serialize(),
            senderKeys, membersAsData, adminsAsData)
        for (member in members) {
            val job = ClosedGroupUpdateMessageSendJob(member, closedGroupUpdateKind)
            ApplicationContext.getInstance(context).jobManager.add(job)
        }
        // TODO: Wait for the messages to finish sending
        // Add the group to the user's set of public keys to poll for
        DatabaseFactory.getSSKDatabase(context).setClosedGroupPrivateKey(groupPublicKey, groupKeyPair.hexEncodedPrivateKey)
        // Notify the user
        // TODO: Implement
        // Return
        return Promise.of(Unit)
    }

    public fun addMembers(context: Context, newMembers: Collection<String>, groupPublicKey: String) {
        // Prepare
        val groupDB = DatabaseFactory.getGroupDatabase(context)
        val groupID = GroupUtil.getEncodedId(Hex.fromStringCondensed(groupPublicKey), false)
        val group = groupDB.getGroup(groupID).orNull()
        if (group == null) {
            Log.d("Loki", "Can't add users to nonexistent closed group.")
            return
        }
        val name = group.title
        val admins = group.admins.map { Hex.fromStringCondensed(it.serialize()) }
        val privateKey = DatabaseFactory.getSSKDatabase(context).getClosedGroupPrivateKey(groupPublicKey)
        if (privateKey == null) {
            Log.d("Loki", "Couldn't get private key for closed group.")
            return
        }
        // Add the members to the member list
        val members = group.members.map { it.serialize() }.toMutableSet()
        members.addAll(newMembers)
        val membersAsData = members.map { Hex.fromStringCondensed(it) }
        // Generate ratchets for the new members
        val senderKeys: List<ClosedGroupSenderKey> = newMembers.map { publicKey ->
            val ratchet = SharedSenderKeysImplementation.shared.generateRatchet(groupPublicKey, publicKey)
            ClosedGroupSenderKey(Hex.fromStringCondensed(ratchet.chainKey), ratchet.keyIndex, Hex.fromStringCondensed(publicKey))
        }
        // Send a closed group update message to the existing members with the new members' ratchets (this message is aimed at the group)
        val closedGroupUpdateKind = ClosedGroupUpdateMessageSendJob.Kind.Info(Hex.fromStringCondensed(groupPublicKey), name,
            senderKeys, membersAsData, admins)
        val job = ClosedGroupUpdateMessageSendJob(groupPublicKey, closedGroupUpdateKind)
        ApplicationContext.getInstance(context).jobManager.add(job)
        // Establish sessions if needed
        establishSessionsWithMembersIfNeeded(context, newMembers)
        // Send closed group update messages to the new members using established channels
        for (member in members) {
            @Suppress("NAME_SHADOWING")
            val closedGroupUpdateKind = ClosedGroupUpdateMessageSendJob.Kind.Info(Hex.fromStringCondensed(groupPublicKey), name,
                senderKeys, membersAsData, admins)
            @Suppress("NAME_SHADOWING")
            val job = ClosedGroupUpdateMessageSendJob(member, closedGroupUpdateKind)
            ApplicationContext.getInstance(context).jobManager.add(job)
        }
        // Update the group
        groupDB.updateMembers(groupID, members.map { Address.fromSerialized(it) })
        // Notify the user
        // TODO: Implement
    }

    public fun leave(context: Context, groupPublicKey: String) {
        val userPublicKey = TextSecurePreferences.getLocalNumber(context)
        removeMembers(context, setOf( userPublicKey ), groupPublicKey)
    }

    public fun removeMembers(context: Context, membersToRemove: Collection<String>, groupPublicKey: String) {
        val userPublicKey = TextSecurePreferences.getLocalNumber(context)
        val sskDatabase = DatabaseFactory.getSSKDatabase(context)
        val isUserLeaving = membersToRemove.contains(userPublicKey)
        if (isUserLeaving && membersToRemove.count() != 1) {
            Log.d("Loki", "Can't remove self and others simultaneously.")
        }
        val groupDB = DatabaseFactory.getGroupDatabase(context)
        val groupID = GroupUtil.getEncodedId(Hex.fromStringCondensed(groupPublicKey), false)
        val group = groupDB.getGroup(groupID).orNull()
        if (group == null) {
            Log.d("Loki", "Can't add users to nonexistent closed group.")
            return
        }
        val name = group.title
        val admins = group.admins.map { Hex.fromStringCondensed(it.serialize()) }
        // Remove the members from the member list
        val members = group.members.map { it.serialize() }.toSet().minus(membersToRemove)
        val membersAsData = members.map { Hex.fromStringCondensed(it) }
        // Send the update to the group (don't include new ratchets as everyone should regenerate new ratchets individually)
        val closedGroupUpdateKind = ClosedGroupUpdateMessageSendJob.Kind.Info(Hex.fromStringCondensed(groupPublicKey),
            name, setOf(), membersAsData, admins)
        val job = ClosedGroupUpdateMessageSendJob(groupPublicKey, closedGroupUpdateKind)
        ApplicationContext.getInstance(context).jobManager.add(job)
        // Delete all ratchets (it's important that this happens after sending out the update)
        sskDatabase.removeAllClosedGroupRatchets(groupPublicKey)
        // Remove the group from the user's set of public keys to poll for if the user is leaving. Otherwise generate a new ratchet and
        // send it out to all members (minus the removed ones) using established channels.
        if (isUserLeaving) {
            sskDatabase.removeClosedGroupPrivateKey(groupPublicKey)
        } else {
            // Establish sessions if needed
            establishSessionsWithMembersIfNeeded(context, members)
            // Send out the user's new ratchet to all members (minus the removed ones) using established channels
            val userRatchet = SharedSenderKeysImplementation.shared.generateRatchet(groupPublicKey, userPublicKey)
            val userSenderKey = ClosedGroupSenderKey(Hex.fromStringCondensed(userRatchet.chainKey), userRatchet.keyIndex, Hex.fromStringCondensed(userPublicKey))
            for (member in members) {
                @Suppress("NAME_SHADOWING")
                val closedGroupUpdateKind = ClosedGroupUpdateMessageSendJob.Kind.SenderKey(Hex.fromStringCondensed(groupPublicKey), userSenderKey)
                @Suppress("NAME_SHADOWING")
                val job = ClosedGroupUpdateMessageSendJob(groupPublicKey, closedGroupUpdateKind)
                ApplicationContext.getInstance(context).jobManager.add(job)
            }
        }
        // Update the group
        groupDB.updateMembers(groupID, members.map { Address.fromSerialized(it) })
        // Notify the user
        // TODO: Implement
    }

    public fun handleSharedSenderKeysUpdate(context: Context, closedGroupUpdate: SignalServiceProtos.ClosedGroupUpdate) {
        when (closedGroupUpdate.type) {
            SignalServiceProtos.ClosedGroupUpdate.Type.NEW -> handleNewClosedGroup(context, closedGroupUpdate)
            SignalServiceProtos.ClosedGroupUpdate.Type.INFO -> handleClosedGroupUpdate(context, closedGroupUpdate)
            SignalServiceProtos.ClosedGroupUpdate.Type.SENDER_KEY_REQUEST -> handleSenderKeyRequest(context, closedGroupUpdate)
            SignalServiceProtos.ClosedGroupUpdate.Type.SENDER_KEY -> handleSenderKey(context, closedGroupUpdate)
            else -> {
                // Do nothing
            }
        }
    }

    public fun handleNewClosedGroup(context: Context, closedGroupUpdate: SignalServiceProtos.ClosedGroupUpdate) {
        // Prepare
        val sskDatabase = DatabaseFactory.getSSKDatabase(context)
        // Unwrap the message
        val groupPublicKey = closedGroupUpdate.groupPublicKey.toByteArray().toHexString()
        val name = closedGroupUpdate.name
        val groupPrivateKey = closedGroupUpdate.groupPrivateKey.toByteArray()
        val senderKeys = closedGroupUpdate.senderKeysList.map {
            ClosedGroupSenderKey(it.chainKey.toByteArray(), it.keyIndex, it.publicKey.toByteArray())
        }
        val members = closedGroupUpdate.membersList.map { it.toByteArray().toHexString() }
        val admins = closedGroupUpdate.adminsList.map { it.toByteArray().toHexString() }
        // Persist the ratchets
        senderKeys.forEach { senderKey ->
            val ratchet = ClosedGroupRatchet(senderKey.chainKey.toHexString(), senderKey.keyIndex, listOf())
            sskDatabase.setClosedGroupRatchet(groupPublicKey, senderKey.publicKey.toHexString(), ratchet)
        }
        // Create the group
        val groupID = GroupUtil.getEncodedId(Hex.fromStringCondensed(groupPublicKey), false)
        DatabaseFactory.getGroupDatabase(context).create(groupID, name, LinkedList<Address>(members.map { Address.fromSerialized(it) }),
            null, null, LinkedList<Address>(admins.map { Address.fromSerialized(it) }))
        DatabaseFactory.getRecipientDatabase(context).setProfileSharing(Recipient.from(context, Address.fromSerialized(groupID), false), true)
        // Add the group to the user's set of public keys to poll for
        sskDatabase.setClosedGroupPrivateKey(groupPrivateKey.toHexString(), groupPublicKey)
        // Notify the user
        // TODO: Implement
        // Establish sessions if needed
        establishSessionsWithMembersIfNeeded(context, members)
    }

    public fun handleClosedGroupUpdate(context: Context, closedGroupUpdate: SignalServiceProtos.ClosedGroupUpdate) {
        // Prepare
        val userPublicKey = TextSecurePreferences.getLocalNumber(context)
        val sskDatabase = DatabaseFactory.getSSKDatabase(context)
        // Unwrap the message
        val groupPublicKey = closedGroupUpdate.groupPublicKey.toByteArray().toHexString()
        val name = closedGroupUpdate.name
        val senderKeys = closedGroupUpdate.senderKeysList.map {
            ClosedGroupSenderKey(it.chainKey.toByteArray(), it.keyIndex, it.publicKey.toByteArray())
        }
        val members = closedGroupUpdate.membersList.map { it.toByteArray().toHexString() }
        val groupDB = DatabaseFactory.getGroupDatabase(context)
        val groupID = GroupUtil.getEncodedId(Hex.fromStringCondensed(groupPublicKey), false)
        val group = groupDB.getGroup(groupID).orNull()
        if (group == null) {
            Log.d("Loki", "Ignoring closed group update for nonexistent group.")
            return
        }
        val oldMembers = group.members.map { it.serialize() }
        val senderPublicKey = "" // TODO
        // Check that the sender is a member of the group (before the update)
        if (!oldMembers.contains(senderPublicKey)) {
            Log.d("Loki", "Ignoring closed group info message from non-member.")
        }
        // Store the ratchets for any new members (it's important that this happens before the code below)
        senderKeys.forEach { senderKey ->
            // TODO: Ignore sender keys if the public key they specify isn't a member of the group
            val ratchet = ClosedGroupRatchet(senderKey.chainKey.toHexString(), senderKey.keyIndex, listOf())
            sskDatabase.setClosedGroupRatchet(groupPublicKey, senderKey.publicKey.toHexString(), ratchet)
        }
        // Delete all ratchets and either:
        // • Send out the user's new ratchet using established channels if other members of the group left or were removed
        // • Remove the group from the user's set of public keys to poll for if the current user was among the members that were removed
        val wasUserRemoved = !members.contains(userPublicKey)
        if (members.toSet().intersect(oldMembers) != oldMembers.toSet()) {
            sskDatabase.removeAllClosedGroupRatchets(groupPublicKey)
            if (wasUserRemoved) {
                sskDatabase.removeClosedGroupPrivateKey(groupPublicKey)
            } else {
                establishSessionsWithMembersIfNeeded(context, members)
                val userRatchet = SharedSenderKeysImplementation.shared.generateRatchet(groupPublicKey, userPublicKey)
                val userSenderKey = ClosedGroupSenderKey(Hex.fromStringCondensed(userRatchet.chainKey), userRatchet.keyIndex, Hex.fromStringCondensed(userPublicKey))
                for (member in members) {
                    val closedGroupUpdateKind = ClosedGroupUpdateMessageSendJob.Kind.SenderKey(Hex.fromStringCondensed(groupPublicKey), userSenderKey)
                    val job = ClosedGroupUpdateMessageSendJob(groupPublicKey, closedGroupUpdateKind)
                    ApplicationContext.getInstance(context).jobManager.add(job)
                }
            }
        }
        // Update the group
        groupDB.updateTitle(groupID, name)
        groupDB.updateMembers(groupID, members.map { Address.fromSerialized(it) })
        // Notify the user
        // TODO: Implement
    }

    public fun handleSenderKeyRequest(context: Context, closedGroupUpdate: SignalServiceProtos.ClosedGroupUpdate) {
        // Prepare
        val userPublicKey = TextSecurePreferences.getLocalNumber(context)
        val groupPublicKey = closedGroupUpdate.groupPublicKey.toByteArray().toHexString()
        val groupDB = DatabaseFactory.getGroupDatabase(context)
        val groupID = GroupUtil.getEncodedId(Hex.fromStringCondensed(groupPublicKey), false)
        val group = groupDB.getGroup(groupID).orNull()
        if (group == null) {
            Log.d("Loki", "Ignoring sender key request for nonexistent group.")
            return
        }
        // Check that the requesting user is a member of the group
        val senderPublicKey = ""
        if (!group.members.map { it.serialize() }.contains(senderPublicKey)) {
            Log.d("Loki", "Ignoring sender key request from non-member.")
        }
        // Respond to the request
        ApplicationContext.getInstance(context).sendSessionRequestIfNeeded(senderPublicKey)
        val userRatchet = SharedSenderKeysImplementation.shared.generateRatchet(groupPublicKey, userPublicKey)
        val userSenderKey = ClosedGroupSenderKey(Hex.fromStringCondensed(userRatchet.chainKey), userRatchet.keyIndex, Hex.fromStringCondensed(userPublicKey))
        val closedGroupUpdateKind = ClosedGroupUpdateMessageSendJob.Kind.SenderKey(Hex.fromStringCondensed(groupPublicKey), userSenderKey)
        val job = ClosedGroupUpdateMessageSendJob(senderPublicKey, closedGroupUpdateKind)
        ApplicationContext.getInstance(context).jobManager.add(job)
    }

    public fun handleSenderKey(context: Context, closedGroupUpdate: SignalServiceProtos.ClosedGroupUpdate) {
        // Prepare
        val sskDatabase = DatabaseFactory.getSSKDatabase(context)
        val groupPublicKey = closedGroupUpdate.groupPublicKey.toByteArray().toHexString()
        val groupDB = DatabaseFactory.getGroupDatabase(context)
        val groupID = GroupUtil.getEncodedId(Hex.fromStringCondensed(groupPublicKey), false)
        val group = groupDB.getGroup(groupID).orNull()
        if (group == null) {
            Log.d("Loki", "Ignoring closed group sender key for nonexistent group.")
            return
        }
        val senderKeyProto = closedGroupUpdate.senderKeysList.firstOrNull()
        if (senderKeyProto == null) {
            Log.d("Loki", "Ignoring invalid closed group sender key.")
            return
        }
        val senderKey = ClosedGroupSenderKey(senderKeyProto.chainKey.toByteArray(), senderKeyProto.keyIndex, senderKeyProto.publicKey.toByteArray())
        val senderPublicKey = senderKeyProto.publicKey.toByteArray().toHexString()
        // Check that the sending user is a member of the group
        if (!group.members.map { it.serialize() }.contains(senderPublicKey)) {
            Log.d("Loki", "Ignoring closed group sender key from non-member.")
        }
        // Store the sender key
        val ratchet = ClosedGroupRatchet(senderKey.chainKey.toHexString(), senderKey.keyIndex, listOf())
        sskDatabase.setClosedGroupRatchet(groupPublicKey, senderPublicKey, ratchet)
    }

    @JvmStatic
    fun shouldIgnoreContentMessage(context: Context, address: Address, groupID: String?, senderPublicKey: String): Boolean {
        if (!address.isClosedGroup || groupID == null) { return false }
        /*
        FileServerAPI.shared.getDeviceLinks(senderPublicKey).timeout(6000).get()
        val senderMasterPublicKey = MultiDeviceProtocol.shared.getMasterDevice(senderPublicKey)
        val publicKeyToCheckFor = senderMasterPublicKey ?: senderPublicKey
         */
        val members = DatabaseFactory.getGroupDatabase(context).getGroupMembers(groupID, true)
        return !members.contains(recipient(context, senderPublicKey))
    }

    @JvmStatic
    fun getMessageDestinations(context: Context, groupID: String): List<Address> {
        if (GroupUtil.isRSSFeed(groupID)) { return listOf() }
        if (GroupUtil.isOpenGroup(groupID)) {
            return listOf( Address.fromSerialized(groupID) )
        } else {
            // TODO: Shared sender keys
            return DatabaseFactory.getGroupDatabase(context).getGroupMembers(groupID, false).map { it.address }
            /*
            return FileServerAPI.shared.getDeviceLinks(members.map { it.address.serialize() }.toSet()).map {
                val result = members.flatMap { member ->
                    MultiDeviceProtocol.shared.getAllLinkedDevices(member.address.serialize()).map { Address.fromSerialized(it) }
                }.toMutableSet()
                val userMasterPublicKey = TextSecurePreferences.getMasterHexEncodedPublicKey(context)
                if (userMasterPublicKey != null && result.contains(Address.fromSerialized(userMasterPublicKey))) {
                    result.remove(Address.fromSerialized(userMasterPublicKey))
                }
                val userPublicKey = TextSecurePreferences.getLocalNumber(context)
                if (userPublicKey != null && result.contains(Address.fromSerialized(userPublicKey))) {
                    result.remove(Address.fromSerialized(userPublicKey))
                }
                result.toList()
            }
             */
        }
    }

    @JvmStatic
    fun leaveLegacyGroup(context: Context, recipient: Recipient): Boolean {
        if (!recipient.address.isClosedGroup) { return true }
        val threadID = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient)
        val message = GroupUtil.createGroupLeaveMessage(context, recipient).orNull()
        if (threadID < 0 || message == null) { return false }
        MessageSender.send(context, message, threadID, false, null)
        /*
        val masterPublicKey = TextSecurePreferences.getMasterHexEncodedPublicKey(context)
        val publicKeyToRemove = masterPublicKey ?: TextSecurePreferences.getLocalNumber(context)
         */
        val userPublicKey = TextSecurePreferences.getLocalNumber(context)
        val groupDatabase = DatabaseFactory.getGroupDatabase(context)
        val groupID = recipient.address.toGroupString()
        groupDatabase.setActive(groupID, false)
        groupDatabase.remove(groupID, Address.fromSerialized(userPublicKey))
        return true
    }

    @JvmStatic
    fun establishSessionsWithMembersIfNeeded(context: Context, members: Collection<String>) {
        @Suppress("NAME_SHADOWING") val members = members.toMutableSet()
        /*
        val allDevices = members.flatMap { member ->
            MultiDeviceProtocol.shared.getAllLinkedDevices(member)
        }.toMutableSet()
        val userMasterPublicKey = TextSecurePreferences.getMasterHexEncodedPublicKey(context)
        if (userMasterPublicKey != null && allDevices.contains(userMasterPublicKey)) {
            allDevices.remove(userMasterPublicKey)
        }
         */
        val userPublicKey = TextSecurePreferences.getLocalNumber(context)
        if (userPublicKey != null && members.contains(userPublicKey)) {
            members.remove(userPublicKey)
        }
        for (member in members) {
            ApplicationContext.getInstance(context).sendSessionRequestIfNeeded(member)
        }
    }
}