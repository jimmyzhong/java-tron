package org.tron.core.actuator;

import static org.tron.core.utils.ZenChainParams.ZC_ENCCIPHERTEXT_SIZE;
import static org.tron.core.utils.ZenChainParams.ZC_OUTCIPHERTEXT_SIZE;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.tron.common.utils.Commons;
import org.tron.common.utils.DBConfig;
import org.tron.common.utils.Sha256Hash;
import org.tron.common.zksnark.IncrementalMerkleTreeContainer;
import org.tron.common.zksnark.JLibrustzcash;
import org.tron.common.zksnark.LibrustzcashParam.CheckOutputParams;
import org.tron.common.zksnark.LibrustzcashParam.CheckSpendParams;
import org.tron.common.zksnark.LibrustzcashParam.FinalCheckParams;
import org.tron.common.zksnark.MerkleContainer;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BytesCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.exception.BalanceInsufficientException;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.exception.ZkProofValidateException;
import org.tron.core.exception.ZksnarkException;
import org.tron.core.store.AccountStore;
import org.tron.core.store.AssetIssueStore;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.core.store.NullifierStore;
import org.tron.core.store.ZKProofStore;
import org.tron.core.utils.TransactionUtil;
import org.tron.protos.Protocol.AccountType;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.Protocol.Transaction.Result.code;
import org.tron.protos.contract.ShieldContract.ReceiveDescription;
import org.tron.protos.contract.ShieldContract.ShieldedTransferContract;
import org.tron.protos.contract.ShieldContract.SpendDescription;
import org.tron.common.utils.ByteArray;
import org.tron.core.capsule.TransactionCapsule;


@Slf4j(topic = "actuator")
public class ShieldedTransferActuator extends AbstractActuator {
  // adjust monitor bug
  private static boolean adjustMonitorResult = true;

  public static String zenTokenId;
  private ShieldedTransferContract shieldedTransferContract;

  public ShieldedTransferActuator() {
    super(ContractType.ShieldedTransferContract, ShieldedTransferContract.class);
    zenTokenId = DBConfig.getZenTokenId();
  }

  @Override
  public boolean execute(Object result)
      throws ContractExeException {
    TransactionResultCapsule ret = (TransactionResultCapsule) result;
    if (Objects.isNull(ret)) {
      throw new RuntimeException("TransactionResultCapsule is null");
    }

    AccountStore accountStore = chainBaseManager.getAccountStore();
    AssetIssueStore assetIssueStore = chainBaseManager.getAssetIssueStore();
    DynamicPropertiesStore dynamicStore = chainBaseManager.getDynamicPropertiesStore();
    try {
      shieldedTransferContract = any.unpack(ShieldedTransferContract.class);
    } catch (InvalidProtocolBufferException e) {
      logger.debug(e.getMessage(), e);
      throw new ContractExeException(e.getMessage());
    }

    long fee = calcFee(shieldedTransferContract);
    try {
      if (shieldedTransferContract.getTransparentFromAddress().toByteArray().length > 0) {
        executeTransparentFrom(shieldedTransferContract.getTransparentFromAddress().toByteArray(),
            shieldedTransferContract.getFromAmount(), ret, fee);
      }
      Commons.adjustAssetBalanceV2(accountStore.getBlackhole().createDbKey(),
          zenTokenId, fee, accountStore, assetIssueStore, dynamicStore);
    } catch (BalanceInsufficientException e) {
      logger.debug(e.getMessage(), e);
      ret.setStatus(0, code.FAILED);
      ret.setShieldedTransactionFee(fee);
      throw new ContractExeException(e.getMessage());
    }

    executeShielded(shieldedTransferContract.getSpendDescriptionList(),
        shieldedTransferContract.getReceiveDescriptionList(), ret, fee);

    if (shieldedTransferContract.getTransparentToAddress().toByteArray().length > 0) {
      executeTransparentTo(shieldedTransferContract.getTransparentToAddress().toByteArray(),
          shieldedTransferContract.getToAmount(), ret, fee);
    }

    //adjust and verify total shielded pool value
    try {
      Commons.adjustTotalShieldedPoolValue(
          Math.addExact(Math.subtractExact(shieldedTransferContract.getToAmount(),
              shieldedTransferContract.getFromAmount()), fee), dynamicStore);
    } catch (ArithmeticException | BalanceInsufficientException e) {
      logger.debug(e.getMessage(), e);
      ret.setStatus(0, code.FAILED);
      ret.setShieldedTransactionFee(fee);
      throw new ContractExeException(e.getMessage());
    }

    ret.setStatus(0, code.SUCESS);
    ret.setShieldedTransactionFee(fee);

    setAndCheckMonitorMerkleTree(shieldedTransferContract);
    return true;
  }

  private void executeTransparentFrom(byte[] ownerAddress, long amount,
      TransactionResultCapsule ret, long fee)
      throws ContractExeException {
    AccountStore accountStore = chainBaseManager.getAccountStore();
    AssetIssueStore assetIssueStore = chainBaseManager.getAssetIssueStore();
    DynamicPropertiesStore dynamicStore = chainBaseManager.getDynamicPropertiesStore();
    try {
      Commons.adjustAssetBalanceV2(ownerAddress, zenTokenId, -amount, accountStore, assetIssueStore,
          dynamicStore);
    } catch (BalanceInsufficientException e) {
      ret.setStatus(0, code.FAILED);
      ret.setShieldedTransactionFee(fee);
      throw new ContractExeException(e.getMessage());
    }
  }

  private void executeTransparentTo(byte[] toAddress, long amount, TransactionResultCapsule ret,
      long fee)
      throws ContractExeException {
    AccountStore accountStore = chainBaseManager.getAccountStore();
    AssetIssueStore assetIssueStore = chainBaseManager.getAssetIssueStore();
    DynamicPropertiesStore dynamicStore = chainBaseManager.getDynamicPropertiesStore();
    try {
      AccountCapsule toAccount = accountStore.get(toAddress);
      if (toAccount == null) {
        boolean withDefaultPermission =
            dynamicStore.getAllowMultiSign() == 1;
        toAccount = new AccountCapsule(ByteString.copyFrom(toAddress), AccountType.Normal,
            dynamicStore.getLatestBlockHeaderTimestamp(), withDefaultPermission, dynamicStore);
        accountStore.put(toAddress, toAccount);
      }
      Commons.adjustAssetBalanceV2(toAddress, zenTokenId, amount, accountStore, assetIssueStore,
          dynamicStore);
    } catch (BalanceInsufficientException e) {
      ret.setStatus(0, code.FAILED);
      ret.setShieldedTransactionFee(fee);
      throw new ContractExeException(e.getMessage());
    }
  }

  //record shielded transaction data.
  private void executeShielded(List<SpendDescription> spends, List<ReceiveDescription> receives,
      TransactionResultCapsule ret, long fee)
      throws ContractExeException {

    NullifierStore nullifierStore = chainBaseManager.getNullifierStore();
    MerkleContainer merkleContainer = chainBaseManager.getMerkleContainer();
    //handle spends
    for (SpendDescription spend : spends) {
      if (nullifierStore.has(
          new BytesCapsule(spend.getNullifier().toByteArray()).getData())) {
        ret.setStatus(fee, code.FAILED);
        ret.setShieldedTransactionFee(fee);
        throw new ContractExeException("double spend");
      }
      nullifierStore.put(new BytesCapsule(spend.getNullifier().toByteArray()));
    }
    if (DBConfig.isFullNodeAllowShieldedTransaction()) {
      IncrementalMerkleTreeContainer currentMerkle = merkleContainer.getCurrentMerkle();
      try {
        currentMerkle.wfcheck();
      } catch (ZksnarkException e) {
        ret.setStatus(fee, code.FAILED);
        ret.setShieldedTransactionFee(fee);
        throw new ContractExeException(e.getMessage());
      }
      //handle receives
      for (ReceiveDescription receive : receives) {
        try {
          merkleContainer
              .saveCmIntoMerkleTree(currentMerkle, receive.getNoteCommitment().toByteArray());
        } catch (ZksnarkException e) {
          ret.setStatus(0, code.FAILED);
          ret.setShieldedTransactionFee(fee);
          throw new ContractExeException(e.getMessage());
        }
      }
      merkleContainer.setCurrentMerkle(currentMerkle);
    }
  }

  @Override
  public boolean validate() throws ContractValidateException {
    if (this.any == null) {
      throw new ContractValidateException("No contract!");
    }
    if (chainBaseManager == null) {
      throw new ContractValidateException("No account store or dynamic store!");
    }
    DynamicPropertiesStore dynamicStore = chainBaseManager.getDynamicPropertiesStore();
    NullifierStore nullifierStore = chainBaseManager.getNullifierStore();
    MerkleContainer merkleContainer = chainBaseManager.getMerkleContainer();
    try {
      shieldedTransferContract = any.unpack(ShieldedTransferContract.class);
    } catch (InvalidProtocolBufferException e) {
      logger.debug(e.getMessage(), e);
      throw new ContractValidateException(e.getMessage());
    }

    if (dynamicStore.getAllowSameTokenName() != 1) {
      throw new ContractValidateException("shielded transaction is not allowed before "
          + "ALLOW_SAME_TOKEN_NAME is opened by the committee");
    }

    if (!dynamicStore.supportShieldedTransaction()) {
      throw new ContractValidateException("Not support Shielded Transaction, need to be opened by"
          + " the committee");
    }

    long fee = calcFee(shieldedTransferContract);
    //transparent verification
    checkSender(shieldedTransferContract);
    checkReceiver(shieldedTransferContract);
    validateTransparent(shieldedTransferContract, fee);

    List<SpendDescription> spendDescriptions = shieldedTransferContract.getSpendDescriptionList();
    // check duplicate sapling nullifiers
    if (CollectionUtils.isNotEmpty(spendDescriptions)) {
      HashSet<ByteString> nfSet = new HashSet<>();
      for (SpendDescription spendDescription : spendDescriptions) {
        if (nfSet.contains(spendDescription.getNullifier())) {
          throw new ContractValidateException("duplicate sapling nullifiers in this transaction");
        }
        nfSet.add(spendDescription.getNullifier());
        if (DBConfig.isFullNodeAllowShieldedTransaction()
            && !merkleContainer.merkleRootExist(spendDescription.getAnchor().toByteArray())) {
          throw new ContractValidateException("Rt is invalid.");
        }
        if (nullifierStore.has(spendDescription.getNullifier().toByteArray())) {
          throw new ContractValidateException("note has been spend in this transaction");
        }
      }
    }

    List<ReceiveDescription> receiveDescriptions = shieldedTransferContract
        .getReceiveDescriptionList();

    HashSet<ByteString> receiveSet = new HashSet<>();
    for (ReceiveDescription receiveDescription : receiveDescriptions) {
      if (receiveSet.contains(receiveDescription.getNoteCommitment())) {
        throw new ContractValidateException("duplicate cm in receive_description");
      }
      receiveSet.add(receiveDescription.getNoteCommitment());
    }
    if (CollectionUtils.isEmpty(spendDescriptions)
        && CollectionUtils.isEmpty(receiveDescriptions)) {
      throw new ContractValidateException("no Description found in transaction");
    }

    //check spendProofs receiveProofs and Binding sign hash
    try {
      checkProof(spendDescriptions, receiveDescriptions, fee);
    } catch (ZkProofValidateException e) {
      if (e.isFirstValidated()) {
        recordProof(tx.getTransactionId(), false);
      }
      throw e;
    }

    return true;
  }

  private void checkProof(List<SpendDescription> spendDescriptions,
      List<ReceiveDescription> receiveDescriptions, long fee) throws ZkProofValidateException {
    DynamicPropertiesStore dynamicStore = chainBaseManager.getDynamicPropertiesStore();
    ZKProofStore proofStore = chainBaseManager.getProofStore();
    if (proofStore.has(tx.getTransactionId().getBytes())) {
      if (proofStore.get(tx.getTransactionId().getBytes())) {
        return;
      } else {
        throw new ZkProofValidateException("record is fail, skip proof", false);
      }
    }

    byte[] signHash = TransactionUtil
        .getShieldTransactionHashIgnoreTypeException(tx.getInstance());

    if (CollectionUtils.isNotEmpty(spendDescriptions)
        || CollectionUtils.isNotEmpty(receiveDescriptions)) {
      long ctx = JLibrustzcash.librustzcashSaplingVerificationCtxInit();
      try {
        for (SpendDescription spendDescription : spendDescriptions) {
          if (!JLibrustzcash.librustzcashSaplingCheckSpend(
              new CheckSpendParams(ctx,
                  spendDescription.getValueCommitment().toByteArray(),
                  spendDescription.getAnchor().toByteArray(),
                  spendDescription.getNullifier().toByteArray(),
                  spendDescription.getRk().toByteArray(),
                  spendDescription.getZkproof().toByteArray(),
                  spendDescription.getSpendAuthoritySignature().toByteArray(),
                  signHash)
          )) {
            throw new ZkProofValidateException("librustzcashSaplingCheckSpend error", true);
          }
        }

        for (ReceiveDescription receiveDescription : receiveDescriptions) {
          if (receiveDescription.getCEnc().size() != ZC_ENCCIPHERTEXT_SIZE
              || receiveDescription.getCOut().size() != ZC_OUTCIPHERTEXT_SIZE) {
            throw new ZkProofValidateException("Cout or CEnc size error", true);
          }
          if (!JLibrustzcash.librustzcashSaplingCheckOutput(
              new CheckOutputParams(ctx,
                  receiveDescription.getValueCommitment().toByteArray(),
                  receiveDescription.getNoteCommitment().toByteArray(),
                  receiveDescription.getEpk().toByteArray(),
                  receiveDescription.getZkproof().toByteArray())
          )) {
            throw new ZkProofValidateException("librustzcashSaplingCheckOutput error", true);
          }
        }

        long valueBalance;
        long totalShieldedPoolValue = dynamicStore
            .getTotalShieldedPoolValue();
        try {
          valueBalance = Math.addExact(Math.subtractExact(shieldedTransferContract.getToAmount(),
              shieldedTransferContract.getFromAmount()), fee);
          totalShieldedPoolValue = Math.subtractExact(totalShieldedPoolValue, valueBalance);
        } catch (ArithmeticException e) {
          logger.debug(e.getMessage(), e);
          throw new ZkProofValidateException(e.getMessage(), true);
        }

        if (totalShieldedPoolValue < 0) {
          throw new ZkProofValidateException("shieldedPoolValue error", true);
        }

        if (!JLibrustzcash.librustzcashSaplingFinalCheck(
            new FinalCheckParams(ctx,
                valueBalance,
                shieldedTransferContract.getBindingSignature().toByteArray(),
                signHash)
        )) {
          throw new ZkProofValidateException("librustzcashSaplingFinalCheck error", true);
        }
      } catch (ZksnarkException e) {
        throw new ZkProofValidateException(e.getMessage(), true);
      } finally {
        JLibrustzcash.librustzcashSaplingVerificationCtxFree(ctx);
      }
    }

    recordProof(tx.getTransactionId(), true);
  }

  private void recordProof(Sha256Hash tid, boolean result) {
    ZKProofStore proofStore = chainBaseManager.getProofStore();
    proofStore.put(tid.getBytes(), result);
  }


  private void checkSender(ShieldedTransferContract shieldedTransferContract)
      throws ContractValidateException {
    if (!shieldedTransferContract.getTransparentFromAddress().isEmpty()
        && shieldedTransferContract.getSpendDescriptionCount() > 0) {
      throw new ContractValidateException("ShieldedTransferContract error, more than 1 senders");
    }
    if (shieldedTransferContract.getTransparentFromAddress().isEmpty()
        && shieldedTransferContract.getSpendDescriptionCount() == 0) {
      throw new ContractValidateException("ShieldedTransferContract error, no sender");
    }
    if (shieldedTransferContract.getSpendDescriptionCount() > 1) {
      throw new ContractValidateException("ShieldedTransferContract error, number of spend notes"
          + " should not be more than 1");
    }
  }

  private void checkReceiver(ShieldedTransferContract shieldedTransferContract)
      throws ContractValidateException {
    if (shieldedTransferContract.getReceiveDescriptionCount() == 0) {
      throw new ContractValidateException("ShieldedTransferContract error, no output cm");
    }
    if (shieldedTransferContract.getReceiveDescriptionCount() > 2) {
      throw new ContractValidateException("ShieldedTransferContract error, number of receivers"
          + " should not be more than 2");
    }
  }

  private void validateTransparent(ShieldedTransferContract shieldedTransferContract, long fee)
      throws ContractValidateException {
    boolean hasTransparentFrom;
    boolean hasTransparentTo;
    byte[] toAddress = shieldedTransferContract.getTransparentToAddress().toByteArray();
    byte[] ownerAddress = shieldedTransferContract.getTransparentFromAddress().toByteArray();

    hasTransparentFrom = (ownerAddress.length > 0);
    hasTransparentTo = (toAddress.length > 0);
    AccountStore accountStore = chainBaseManager.getAccountStore();
    long fromAmount = shieldedTransferContract.getFromAmount();
    long toAmount = shieldedTransferContract.getToAmount();
    if (fromAmount < 0) {
      throw new ContractValidateException("from_amount should not be less than 0");
    }
    if (toAmount < 0) {
      throw new ContractValidateException("to_amount should not be less than 0");
    }

    if (hasTransparentFrom && !Commons.addressValid(ownerAddress)) {
      throw new ContractValidateException("Invalid transparent_from_address");
    }
    if (!hasTransparentFrom && fromAmount != 0) {
      throw new ContractValidateException("no transparent_from_address, from_amount should be 0");
    }
    if (hasTransparentTo && !Commons.addressValid(toAddress)) {
      throw new ContractValidateException("Invalid transparent_to_address");
    }
    if (!hasTransparentTo && toAmount != 0) {
      throw new ContractValidateException("no transparent_to_address, to_amount should be 0");
    }
    if (hasTransparentFrom && hasTransparentTo && Arrays.equals(toAddress, ownerAddress)) {
      throw new ContractValidateException("Can't transfer zen to yourself");
    }

    if (hasTransparentFrom) {
      AccountCapsule ownerAccount = accountStore.get(ownerAddress);
      if (ownerAccount == null) {
        throw new ContractValidateException("Validate ShieldedTransferContract error, "
            + "no OwnerAccount");
      }
      long balance = getZenBalance(ownerAccount);
      if (fromAmount <= 0) {
        throw new ContractValidateException("from_amount must be greater than 0");
      }
      if (balance < fromAmount) {
        throw new ContractValidateException(
            "Validate ShieldedTransferContract error, balance is not sufficient");
      }
      if (fromAmount <= fee) {
        throw new ContractValidateException(
            "Validate ShieldedTransferContract error, fromAmount should be great than fee");
      }
    }

    if (hasTransparentTo) {
      if (toAmount <= 0) {
        throw new ContractValidateException("to_amount must be greater than 0");
      }
      AccountCapsule toAccount = accountStore.get(toAddress);
      if (toAccount != null) {
        try {
          Math.addExact(getZenBalance(toAccount), toAmount);
        } catch (ArithmeticException e) {
          logger.debug(e.getMessage(), e);
          throw new ContractValidateException(e.getMessage());
        }
      }
    }
  }

  private long getZenBalance(AccountCapsule account) {
    if (account.getAssetMapV2().get(zenTokenId) == null) {
      return 0L;
    } else {
      return account.getAssetMapV2().get(zenTokenId);
    }
  }

  private void setAndCheckMonitorMerkleTree(ShieldedTransferContract shieldedTransferContract) {
    setShieldedTransactionParameter(shieldedTransferContract);
    if (DBConfig.isMonitorShieldCheckLog()) {
      checkDataDBAndMonitor();
    }
  }

  private void checkDataDBAndMonitor() {
    long cmNumberFromDB = chainBaseManager.getMerkleContainer().getCurrentMerkle().size();
    long nullifierNumberFromDB = chainBaseManager.getNullifierStore().size(); //耗时比较久，将近0.5s
    long shieldedValueFromDB = chainBaseManager.getDynamicPropertiesStore()
        .getTotalShieldedPoolValue();
    long cmNumberFromTransaction = chainBaseManager.getDynamicPropertiesStore()
        .getTotalCMNumberFromTransactions();
    long nullifierNumberFromTransaction = chainBaseManager.getDynamicPropertiesStore()
        .getTotalNullifierNumber();
    long shieldedValueFromTransaction = chainBaseManager.getDynamicPropertiesStore()
        .getShieldValueFromTransaction();

    long publicToOneShielded = chainBaseManager.getDynamicPropertiesStore()
        .getPublicToOneShieldedNumber();
    long publicToTwoSHielded = chainBaseManager.getDynamicPropertiesStore()
        .getPublicToTwoShieldedNumber();
    long shieldedToOneShielded = chainBaseManager.getDynamicPropertiesStore()
        .getShieldedToOneShieldedNumber();
    long shieldedToTwoShielded = chainBaseManager.getDynamicPropertiesStore()
        .getShieldedToTwoShieldedNumber();
    long shieldedToPublicOneShielded = chainBaseManager.getDynamicPropertiesStore()
        .getShieldedToOneShieldedAndPublicNumber();
    long shieldedToPublicTwoShielded = chainBaseManager.getDynamicPropertiesStore()
        .getShieldedToTwoShieldedAndPublicNumber();
    long shieldedToPublic = chainBaseManager.getDynamicPropertiesStore()
        .getShieldedToPublicNumber();

    long totalCmFromTransaction =
        (publicToOneShielded + shieldedToOneShielded + shieldedToPublicOneShielded)
            + (publicToTwoSHielded + shieldedToTwoShielded + shieldedToPublicTwoShielded) * 2;
    long totalNullFromTransaction =
        shieldedToOneShielded + shieldedToTwoShielded + shieldedToPublicOneShielded
            + shieldedToPublicTwoShielded + shieldedToPublic;

    logger.info(
        "[setAndCheckMonitorMerkleTree] cmNumberFromDb {} cmNumberFromTransaction {} "
            + "nullifierFromDb {} "
            + "nullifierFromTransaction {} shieldValueFromDb {} shieldValueFromTransaction {} "
            + "totalCmFromTransaction {} totalNullFromTransaction {}",
        cmNumberFromDB, cmNumberFromTransaction, nullifierNumberFromDB,
        nullifierNumberFromTransaction, shieldedValueFromDB, shieldedValueFromTransaction,
        totalCmFromTransaction, totalNullFromTransaction);

    if (cmNumberFromDB != cmNumberFromTransaction ||
        nullifierNumberFromDB != nullifierNumberFromTransaction ||
        shieldedValueFromDB != shieldedValueFromTransaction ||
        cmNumberFromDB != totalCmFromTransaction ||
        nullifierNumberFromDB != totalNullFromTransaction) {
      // adjust because of bug
      if (adjustMonitorResult) {
        long deltaFee = shieldedValueFromTransaction - shieldedValueFromDB;
        chainBaseManager.getDynamicPropertiesStore().saveTotalShieldedTransactionsFee(
            chainBaseManager.getDynamicPropertiesStore().getTotalShieldedTransactionsFee() + deltaFee);
        logger.warn("[setAndCheckMonitorMerkleTree] adjust fee, deltaFee = " + deltaFee);
      }

      byte[] signHash = TransactionCapsule.getShieldTransactionHashIgnoreTypeException(tx);
      logger.error(
          "[setAndCheckMonitorMerkleTree] Last BlockNum {} transaction {} shield transaction "
              + "check failure.",
          chainBaseManager.getDynamicPropertiesStore().getLatestBlockHeaderNumber(),
          ByteArray.toHexString(signHash));

//      postAlarmToDingDing(cmNumberFromDB, cmNumberFromTransaction, nullifierNumberFromDB,
//          nullifierNumberFromTransaction, shieldedValueFromDB,shieldedValueFromTransaction);
    } else {
      logger.info("[setAndCheckMonitorMerkleTree] success!");
    }
  }

  private void setShieldedTransactionParameter(ShieldedTransferContract shieldedTransferContract) {
    long newCMNumber = shieldedTransferContract.getReceiveDescriptionCount();
    long newNullifierNumber = shieldedTransferContract.getSpendDescriptionCount();
    long amountFromPublic = shieldedTransferContract.getFromAmount();
    long amountToPublic = shieldedTransferContract.getToAmount();

    chainBaseManager.getDynamicPropertiesStore().saveTotalCMNumberFromTransactions(
        chainBaseManager.getDynamicPropertiesStore().getTotalCMNumberFromTransactions()
            + newCMNumber);
    chainBaseManager.getDynamicPropertiesStore().saveTotalNullifierNumber(
        chainBaseManager.getDynamicPropertiesStore().getTotalNullifierNumber()
            + newNullifierNumber);
    chainBaseManager.getDynamicPropertiesStore().saveTotalAmountFromPulic(
        chainBaseManager.getDynamicPropertiesStore().getTotalAmountFromPulic() + amountFromPublic);
    chainBaseManager.getDynamicPropertiesStore().saveTotalAmountToPublic(
        chainBaseManager.getDynamicPropertiesStore().getTotalAmountToPublic() + amountToPublic);
    chainBaseManager.getDynamicPropertiesStore().saveTotalShieldedTransactionsFee(
        chainBaseManager.getDynamicPropertiesStore().getTotalShieldedTransactionsFee() + calcFee(shieldedTransferContract));
    chainBaseManager.getDynamicPropertiesStore().saveTotalShieldedTransactionNumber(
        chainBaseManager.getDynamicPropertiesStore().getTotalShieldedTransactionNumber() + 1L);

    //set transaction number
    if (amountFromPublic > 0L) {
      if (newCMNumber == 1) {
        chainBaseManager.getDynamicPropertiesStore().savePublicToOneShieldedNumber(
            chainBaseManager.getDynamicPropertiesStore().getPublicToOneShieldedNumber() + 1L);
      } else if (newCMNumber == 2) {
        chainBaseManager.getDynamicPropertiesStore().savePublicToTwoShieldedNumber(
            chainBaseManager.getDynamicPropertiesStore().getPublicToTwoShieldedNumber() + 1L);
      } else {
        logger.error("This is not allowed. public to shielded number {} ", newCMNumber);
      }
    } else {
      if (amountToPublic > 0L) {
        if (newCMNumber == 0) {
          chainBaseManager.getDynamicPropertiesStore().saveShieldedToPublicNumber(
              chainBaseManager.getDynamicPropertiesStore().getShieldedToPublicNumber() + 1L);
        } else if (newCMNumber == 1) {
          chainBaseManager.getDynamicPropertiesStore().saveShieldedToOneShieldedAndPublicNumber(
              chainBaseManager.getDynamicPropertiesStore().getShieldedToOneShieldedAndPublicNumber()
                  + 1L);
        } else if (newCMNumber == 2) {
          chainBaseManager.getDynamicPropertiesStore().saveShieldedToTwoShieldedAndPublicNumber(
              chainBaseManager.getDynamicPropertiesStore().getShieldedToTwoShieldedAndPublicNumber()
                  + 1L);
        } else {
          logger.error("This is not allowed. shielded to public and shielded number {} ",
              newCMNumber);
        }
      } else {
        if (newCMNumber == 1) {
          chainBaseManager.getDynamicPropertiesStore().saveShieldedToOneShieldedNumber(
              chainBaseManager.getDynamicPropertiesStore().getShieldedToOneShieldedNumber() + 1L);
        } else if (newCMNumber == 2) {
          chainBaseManager.getDynamicPropertiesStore().saveShieldedToTwoShieldedNumber(
              chainBaseManager.getDynamicPropertiesStore().getShieldedToTwoShieldedNumber() + 1L);
        } else {
          logger.error("This is not allowed. shielded to shielded number {} ", newCMNumber);
        }
      }
    }
  }

  @Override
  public ByteString getOwnerAddress() throws InvalidProtocolBufferException {
    ByteString owner = any.unpack(ShieldedTransferContract.class).getTransparentFromAddress();
    if (Commons.addressValid(owner.toByteArray())) {
      return owner;
    } else {
      return null;
    }
  }

  private long calcFee(ShieldedTransferContract shieldedTransferContract) {
    byte[] toAddress = shieldedTransferContract.getTransparentToAddress().toByteArray();
    boolean hasTransparentTo = (toAddress.length > 0);
    if (hasTransparentTo) {
      AccountCapsule toAccount = chainBaseManager.getAccountStore().get(toAddress);
      if (toAccount == null) {
        return chainBaseManager.getDynamicPropertiesStore()
            .getShieldedTransactionCreateAccountFee();
      }
    }
    return chainBaseManager.getDynamicPropertiesStore().getShieldedTransactionFee();
  }

  @Override
  public long calcFee() {
    // Abandoned
    return 0;
  }
}
