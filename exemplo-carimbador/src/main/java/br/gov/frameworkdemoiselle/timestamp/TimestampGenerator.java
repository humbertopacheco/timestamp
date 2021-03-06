package br.gov.frameworkdemoiselle.timestamp;

import br.gov.frameworkdemoiselle.certificate.criptography.Digest;
import br.gov.frameworkdemoiselle.certificate.criptography.DigestAlgorithmEnum;
import br.gov.frameworkdemoiselle.certificate.criptography.factory.DigestFactory;
import br.gov.frameworkdemoiselle.timestamp.connector.Connector;
import br.gov.frameworkdemoiselle.timestamp.connector.ConnectorFactory;
import br.gov.frameworkdemoiselle.timestamp.enumeration.ConnectionType;
import br.gov.frameworkdemoiselle.timestamp.enumeration.PKIFailureInfo;
import br.gov.frameworkdemoiselle.timestamp.enumeration.PKIStatus;
import br.gov.frameworkdemoiselle.timestamp.exception.TimestampException;
import br.gov.frameworkdemoiselle.timestamp.signer.RequestSigner;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TSPException;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;
import org.bouncycastle.util.Store;
import org.bouncycastle.util.encoders.Base64;

/**
 *
 * @author 07721825741
 */
public class TimestampGenerator {

    private final static Logger logger = Logger.getLogger(TimestampGenerator.class.getName());
    private InputStream inputStream = null;
    private Timestamp timestamp;
    private TimeStampRequest timeStampRequest;
    private TimeStampResponse timeStampResponse;

    public byte[] createRequest(byte content) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    /**
     *
     * @param original
     * @param keystore
     * @param alias
     * @param digestAlgorithm
     * @return
     * @throws TimestampException
     * @throws IOException
     */
    public byte[] createRequest(byte[] original, KeyStore keystore, String alias, DigestAlgorithmEnum digestAlgorithm) throws TimestampException, IOException {
        logger.log(Level.INFO, "Gerando o digest do conteudo");
        Digest digest = DigestFactory.getInstance().factoryDefault();
        digest.setAlgorithm(digestAlgorithm);
        byte[] hashedMessage = digest.digest(original);
        logger.log(Level.INFO, Base64.toBase64String(hashedMessage));

        logger.log(Level.INFO, "Montando a requisicao para o carimbador de tempo");
        TimeStampRequestGenerator timeStampRequestGenerator = new TimeStampRequestGenerator();
        timeStampRequestGenerator.setReqPolicy(new ASN1ObjectIdentifier("2.16.76.1.6.2"));
        timeStampRequestGenerator.setCertReq(true);
        timeStampRequest = timeStampRequestGenerator.generate(TSPAlgorithms.SHA256, hashedMessage, BigInteger.valueOf(100));
        byte request[] = timeStampRequest.getEncoded();

        logger.info("Efetuando a  assinatura do conteudo");
        RequestSigner requestSigner = new RequestSigner();
        byte[] signedRequest = requestSigner.assinar(keystore, alias, null, request);

        return signedRequest;
    }

    /**
     *
     * @param request
     * @param connectionType
     * @return
     * @throws TimestampException
     */
    public byte[] doTimestamp(byte[] request, ConnectionType connectionType) throws TimestampException {
        try {
            logger.log(Level.INFO, "Iniciando pedido de carimbo de tempo");
            Connector connector = ConnectorFactory.buildConnector(connectionType);
            connector.setHostname("act.serpro.gov.br");
            connector.setPort(318);

            logger.info("Obtendo o response");
            inputStream = connector.connect(request);

            long tempo;
            // Valor do timeout da verificacao de dados disponiveis para leitura
            int timeOut = 3500;
            // Verificando se os 4 bytes iniciais estao disponiveis para leitura
            for (tempo = System.currentTimeMillis() + timeOut; inputStream.available() < 4 && System.currentTimeMillis() < tempo;) {
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            // Lendo tamanho total
            byte[] tamanhoRetorno = new byte[4];
            inputStream.read(tamanhoRetorno, 0, 4);
            int tamanho = new BigInteger(tamanhoRetorno).intValue();

            // Verificando se os bytes na quantidade "tamanho" estao disponiveis
            if (System.currentTimeMillis() < tempo) {
                while (inputStream.available() < tamanho && System.currentTimeMillis() < tempo) {
                    try {
                        Thread.sleep(1L);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                if (System.currentTimeMillis() >= tempo) {
                    System.out.println("Erro timeout ao receber dados");
                }
            } else {
                System.out.println("Erro timeout ao receber dados");
            }

            // Lendo flag
            byte[] flagRetorno = new byte[1];
            inputStream.read(flagRetorno, 0, 1);
            // tamanho total menos o tamanho da flag
            tamanho -= 1;

            // Lendo dados carimbo
            byte[] carimboRetorno = new byte[tamanho];
            inputStream.read(carimboRetorno, 0, tamanho);
            timeStampResponse = new TimeStampResponse(carimboRetorno);

            logger.log(Level.INFO, "PKIStatus = {0}", timeStampResponse.getStatus());

            switch (timeStampResponse.getStatus()) {
                case 0: {
                    logger.log(Level.INFO, PKIStatus.granted.getMessage());
                    break;
                }
                case 1: {
                    logger.log(Level.INFO, PKIStatus.grantedWithMods.getMessage());
                }
                case 2: {
                    logger.log(Level.INFO, PKIStatus.rejection.getMessage());
                    throw new TimestampException(PKIStatus.rejection.getMessage());
                }
                case 3: {
                    logger.log(Level.INFO, PKIStatus.waiting.getMessage());
                    throw new TimestampException(PKIStatus.waiting.getMessage());
                }
                case 4: {
                    logger.log(Level.INFO, PKIStatus.revocationWarning.getMessage());
                    throw new TimestampException(PKIStatus.revocationWarning.getMessage());
                }
                case 5: {
                    logger.log(Level.INFO, PKIStatus.revocationNotification.getMessage());
                    throw new TimestampException(PKIStatus.revocationNotification.getMessage());
                }
            }

            int failInfo = -1;

            if (timeStampResponse.getFailInfo() != null) {
                failInfo = Integer.parseInt(new String(timeStampResponse.getFailInfo().getBytes()));
            }

            logger.log(Level.INFO, "FailInfo = {0}", failInfo);

            switch (failInfo) {
                case 0:
                    logger.log(Level.INFO, PKIFailureInfo.badAlg.getMessage());
                    break;
                case 2:
                    logger.log(Level.INFO, PKIFailureInfo.badRequest.getMessage());
                    break;
                case 5:
                    logger.log(Level.INFO, PKIFailureInfo.badDataFormat.getMessage());
                    break;
                case 14:
                    logger.log(Level.INFO, PKIFailureInfo.timeNotAvailable.getMessage());
                    break;
                case 15:
                    logger.log(Level.INFO, PKIFailureInfo.unacceptedPolicy.getMessage());
                    break;
                case 16:
                    logger.log(Level.INFO, PKIFailureInfo.unacceptedExtension.getMessage());
                    break;
                case 17:
                    logger.log(Level.INFO, PKIFailureInfo.addInfoNotAvailable.getMessage());
                    break;
                case 25:
                    logger.log(Level.INFO, PKIFailureInfo.systemFailure.getMessage());
                    break;
            }

            timeStampResponse.validate(timeStampRequest);
            TimeStampToken timeStampToken = timeStampResponse.getTimeStampToken();
            timestamp = new Timestamp(timeStampToken);

            if (timeStampToken == null) {
                throw new TimestampException("O Token retornou nulo.");
            }

            connector.close();
            return carimboRetorno;

        } catch (Exception e) {
            throw new TimestampException(e.getMessage(), e.getCause());
        }
    }

    /**
     *
     * @param response O carimbo de tempo a ser validado
     * @throws TimestampException
     */
    public void validate(byte[] response) throws TimestampException {
        try {
            Security.addProvider(new BouncyCastleProvider());
            TimeStampResponse tsr = new TimeStampResponse(response);
            TimeStampToken timeStampToken = tsr.getTimeStampToken();
            CMSSignedData s = timeStampToken.toCMSSignedData();

            int verified = 0;

            Store certStore = s.getCertificates();
            SignerInformationStore signers = s.getSignerInfos();
            Collection c = signers.getSigners();
            Iterator it = c.iterator();

            while (it.hasNext()) {
                SignerInformation signer = (SignerInformation) it.next();
                Collection certCollection = certStore.getMatches(signer.getSID());

                Iterator certIt = certCollection.iterator();
                X509CertificateHolder cert = (X509CertificateHolder) certIt.next();

                cert.getExtension(new ASN1ObjectIdentifier("2.5.29.31")).getExtnValue();

                if (signer.verify(new JcaSimpleSignerInfoVerifierBuilder().setProvider("BC").build(cert))) {
                    verified++;
                }
            }

            logger.log(Level.INFO, "Assinaturas Verificadas : {0}", verified);
            this.timestamp = new Timestamp(timeStampToken);
        } catch (TSPException ex) {
            throw new TimestampException(ex.getMessage(), ex.getCause());
        } catch (IOException ex) {
            throw new TimestampException(ex.getMessage(), ex.getCause());
        } catch (CMSException ex) {
            throw new TimestampException(ex.getMessage(), ex.getCause());
        } catch (OperatorCreationException ex) {
            throw new TimestampException(ex.getMessage(), ex.getCause());
        } catch (CertificateException ex) {
            throw new TimestampException(ex.getMessage(), ex.getCause());
        }
    }

    /**
     * Valida um carimnbo de tempo e o documento original
     *
     * @param response O carimbo de tempo a ser validado
     * @param original O arquivo original a ser validado
     * @throws TimestampException
     */
    public void validate(byte[] response, byte[] original) throws TimestampException {
        //Valida a assinatura digital do carimbo de tempo
        this.validate(response);

        //Valida o hash  incluso no carimbo de tempo com hash do arquivo carimbado
        Digest digest = DigestFactory.getInstance().factoryDefault();
        digest.setAlgorithm(DigestAlgorithmEnum.SHA_256);
        digest.digest(original);

        if (Arrays.equals(digest.digest(original), this.timestamp.getMessageImprintDigest())) {
            logger.log(Level.INFO, "Digest do documento conferido com sucesso.");
        } else {
            throw new TimestampException("O documento fornecido nao corresponde ao do carimbo de tempo!");
        }
    }

    public Timestamp getTimestamp() {
        return timestamp;
    }
}
