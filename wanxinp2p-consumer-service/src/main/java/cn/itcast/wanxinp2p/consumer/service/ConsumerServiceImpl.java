package cn.itcast.wanxinp2p.consumer.service;

import cn.itcast.wanxinp2p.api.account.model.AccountDTO;
import cn.itcast.wanxinp2p.api.account.model.AccountRegisterDTO;
import cn.itcast.wanxinp2p.api.consumer.model.BankCardDTO;
import cn.itcast.wanxinp2p.api.consumer.model.ConsumerDTO;
import cn.itcast.wanxinp2p.api.consumer.model.ConsumerRegisterDTO;
import cn.itcast.wanxinp2p.api.consumer.model.ConsumerRequest;
import cn.itcast.wanxinp2p.api.depository.model.DepositoryConsumerResponse;
import cn.itcast.wanxinp2p.api.depository.model.GatewayRequest;
import cn.itcast.wanxinp2p.common.domain.*;
import cn.itcast.wanxinp2p.common.util.CodeNoUtil;
import cn.itcast.wanxinp2p.consumer.agent.AccountApiAgent;
import cn.itcast.wanxinp2p.consumer.agent.DepositoryAgentApiAgent;
import cn.itcast.wanxinp2p.consumer.common.ConsumerErrorCode;
import cn.itcast.wanxinp2p.consumer.entity.BankCard;
import cn.itcast.wanxinp2p.consumer.entity.Consumer;
import cn.itcast.wanxinp2p.consumer.mapper.ConsumerMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.dromara.hmily.annotation.Hmily;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class ConsumerServiceImpl extends ServiceImpl<ConsumerMapper, Consumer> implements ConsumerService {

    @Autowired
    private AccountApiAgent accountApiAgent;

    @Autowired
    private BankCardService bankCardService;

    @Autowired
    private DepositoryAgentApiAgent depositoryAgentApiAgent;



    @Override
    public Integer checkMobile(String mobile) {
        return getByMobile(mobile)!=null?1:0;
    }

    private ConsumerDTO getByMobile(String mobile){
        Consumer consumer=getOne(new QueryWrapper<Consumer>().lambda().eq(Consumer::getMobile,mobile));
        return convertConsumerEntityToDTO(consumer);
    }

    @Override
    @Hmily(confirmMethod = "confirmRegister", cancelMethod = "cancelRegister")
    public void register(ConsumerRegisterDTO consumerRegisterDTO) {
        if(checkMobile(consumerRegisterDTO.getMobile())==1){
            throw new BusinessException(ConsumerErrorCode.E_140107);
        }
        Consumer consumer=new Consumer();
        BeanUtils.copyProperties(consumerRegisterDTO, consumer);
        consumer.setUsername(CodeNoUtil.getNo(CodePrefixCode.CODE_NO_PREFIX));
        consumerRegisterDTO.setUsername(consumer.getUsername());
        consumer.setUserNo(CodeNoUtil.getNo(CodePrefixCode.CODE_REQUEST_PREFIX));
        consumer.setIsBindCard(0);
        save(consumer);

        //远程调用account
        AccountRegisterDTO accountRegisterDTO=new AccountRegisterDTO();
        BeanUtils.copyProperties(consumerRegisterDTO, accountRegisterDTO);
        RestResponse<AccountDTO> restResponse=accountApiAgent.register(accountRegisterDTO);
        if(restResponse.getCode()!= CommonErrorCode.SUCCESS.getCode()){
            throw new BusinessException(ConsumerErrorCode.E_140106);
        }
    }

    @Override
    @Transactional
    public RestResponse<GatewayRequest> createConsumer(ConsumerRequest consumerRequest) {
        ConsumerDTO consumerDTO=getByMobile(consumerRequest.getMobile());
//判断用户是否已开户
        if (consumerDTO.getIsBindCard() == 1) {
            throw new BusinessException(ConsumerErrorCode.E_140105);
        }
//判断银行卡是否已被绑定
        BankCardDTO bankCardDTO = bankCardService
                .getByCardNumber(consumerRequest.getCardNumber());
        if (bankCardDTO != null &&
                bankCardDTO.getStatus()== StatusCode.STATUS_IN.getCode()) {
            throw new BusinessException(ConsumerErrorCode.E_140151);
        }
//更新用户开户信息
        consumerRequest.setId(consumerDTO.getId());
//产生请求流水号和用户编号
        consumerRequest.setUserNo(CodeNoUtil.getNo(CodePrefixCode.CODE_CONSUMER_PREFIX)
        );
        consumerRequest.setRequestNo(CodeNoUtil.getNo(CodePrefixCode.CODE_REQUEST_PREFIX));
//设置查询条件和需要更新的数据
        UpdateWrapper<Consumer> updateWrapper = new UpdateWrapper<>();
        updateWrapper.lambda().eq(Consumer::getMobile, consumerDTO.getMobile());
        updateWrapper.lambda().set(Consumer::getUserNo,
                consumerRequest.getUserNo());
        updateWrapper.lambda().set(Consumer::getRequestNo,
                consumerRequest.getRequestNo());
        updateWrapper.lambda().set(Consumer::getFullname,
                consumerRequest.getFullname());
        updateWrapper.lambda().set(Consumer::getIdNumber,
                consumerRequest.getIdNumber());
        updateWrapper.lambda().set(Consumer::getAuthList, "ALL");
        update(updateWrapper);
//保存用户绑卡信息
        BankCard bankCard = new BankCard();
        bankCard.setConsumerId(consumerDTO.getId());
        bankCard.setBankCode(consumerRequest.getBankCode());
        bankCard.setCardNumber(consumerRequest.getCardNumber());
        bankCard.setMobile(consumerRequest.getMobile());
        bankCard.setStatus(StatusCode.STATUS_OUT.getCode());
        BankCardDTO existBankCard = bankCardService
                .getByConsumerId(bankCard.getConsumerId());
        if (existBankCard != null) {
            bankCard.setId(existBankCard.getId());
        }
        bankCardService.saveOrUpdate(bankCard);
        return depositoryAgentApiAgent.createConsumer(consumerRequest);
    }

    @Override
    @Transactional
    public Boolean modifyResult(DepositoryConsumerResponse response) {
        //1.获取状态
        int status = DepositoryReturnCode.RETURN_CODE_00000.getCode()
                .equals(response.getRespCode()) ? StatusCode.STATUS_IN.getCode()
                :StatusCode.STATUS_FAIL.getCode();
        //2.更新开户结果
        Consumer consumer = getByRequestNo(response.getRequestNo());
        update(Wrappers.<Consumer>lambdaUpdate().eq(Consumer::getId,
                consumer.getId()).set(Consumer::getIsBindCard, status).set(Consumer::getStatus,status));
        //3.更新银行卡信息
        return bankCardService.update(Wrappers.<BankCard>lambdaUpdate()
                .eq(BankCard::getConsumerId, consumer.getId())
                .set(BankCard::getStatus, status).set(BankCard::getBankCode,
                        response.getBankCode())
                .set(BankCard::getBankName, response.getBankName()));
    }

    private Consumer getByRequestNo(String requestNo){
        return getOne(Wrappers.
                <Consumer>lambdaQuery().eq(Consumer::getRequestNo,requestNo));
    }

    public void confirmRegister(ConsumerRegisterDTO consumerRegisterDTO) {
        log.info("execute confirmRegister");
    }
    public void cancelRegister(ConsumerRegisterDTO consumerRegisterDTO) {
        log.info("execute cancelRegister");
        remove(Wrappers.<Consumer>lambdaQuery().eq(Consumer::getMobile,
                consumerRegisterDTO.getMobile()));
    }

    /**
        * entity转为dto
        * @param entity
        * @return
     **/
    private ConsumerDTO convertConsumerEntityToDTO(Consumer entity) {
        if (entity == null) {
            return null;
        }
        ConsumerDTO dto = new ConsumerDTO();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }

}
