package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.ShoppingCart;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.result.Result;
import com.sky.service.ShoppingCartService;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ShoppingCartServiceImpl implements ShoppingCartService {

    @Autowired
    private ShoppingCartMapper  shoppingCartMapper;
    @Autowired
    private DishMapper  dishMapper;
    @Autowired
    private SetmealMapper setmealMapper;
    @Autowired
    private ShoppingCartService shoppingCartService;

    /**
     * 添加购物车
     * @param shoppingCartDTO
     */
    @Override
    public void addShoppingCart(ShoppingCartDTO shoppingCartDTO) {
        // 1) 创建购物车实体对象，后续把前端传入的数据拷贝进来
        ShoppingCart shoppingCart = new ShoppingCart();
        // 2) 将 DTO 中的同名属性复制到购物车实体
        BeanUtils.copyProperties(shoppingCartDTO,shoppingCart);
        // 3) 绑定当前登录用户 id，保证只操作自己的购物车
        shoppingCart.setUserId(BaseContext.getCurrentId());//绑定用户id

        // 4) 按“用户 + 菜品/套餐 + 口味”等条件查询购物车里是否已存在同一商品
        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);

        // 5) 进入“更新数量”分支（按当前代码条件执行）
        if(shoppingCartList != null && shoppingCartList.size() == 1){
            // 5.1) 取出已存在的购物车记录
            shoppingCart = shoppingCartList.get(0);
            // 5.2) 在原数量基础上 +1
            shoppingCart.setNumber(shoppingCart.getNumber()+1);
            // 5.3) 执行更新数量 SQL
            shoppingCartMapper.updateNumberBbId(shoppingCart);
        }else {
            // 6) 进入“新增购物车项”分支，初始数量会设置为 1

            // 6.1) 判断当前添加的是菜品还是套餐
            Long dishId = shoppingCartDTO.getDishId();
            if (dishId != null) {
                // 6.1.1) 菜品：根据 dishId 查询菜品信息
                Dish dish = dishMapper.getById(dishId);
                // 6.1.2) 回填购物车展示字段（名称/图片/金额）
                shoppingCart.setName(dish.getName());
                shoppingCart.setImage(dish.getImage());
                shoppingCart.setAmount(dish.getPrice());
            } else {
                // 6.2.1) 套餐：根据 setmealId 查询套餐信息
                Setmeal setmeal = setmealMapper.getById(shoppingCartDTO.getSetmealId());
                // 6.2.2) 回填购物车展示字段（名称/图片/金额）
                shoppingCart.setName(setmeal.getName());
                shoppingCart.setImage(setmeal.getImage());
                shoppingCart.setAmount(setmeal.getPrice());
            }
            // 6.3) 新增记录默认数量 = 1
            shoppingCart.setNumber(1);
            // 6.4) 记录创建时间
            shoppingCart.setCreateTime(LocalDateTime.now());
            // 6.5) 执行新增 SQL
            shoppingCartMapper.insert(shoppingCart);
        }
    }

    /**
     * 删除购物车一个商品
     * @param shoppingCartDTO
     */
    @Override
    public void subShoppingCart(ShoppingCartDTO shoppingCartDTO) {
        //拷贝
        ShoppingCart shoppingCart = new ShoppingCart();
        BeanUtils.copyProperties(shoppingCartDTO,shoppingCart);
        //设置查询条件，查询当前登录用户的购物车数量
        shoppingCart.setUserId(BaseContext.getCurrentId());

        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);

        if(shoppingCartList != null && shoppingCartList.size() > 0){
            shoppingCart = shoppingCartList.get(0);
            //获取数量
            Integer number = shoppingCart.getNumber();
            if(number == 1){
                //数量为1则之际删除当前记录
                shoppingCartMapper.deleteById(shoppingCart.getId());
            }
            else {
                //数量不为1，修改分数即可
                shoppingCart.setNumber(shoppingCart.getNumber()-1);
                shoppingCartMapper.updateNumberBbId(shoppingCart);
            }

        }

    }

    /**
     * 查看购物车
     * @return
     */
    @Override
    public List<ShoppingCart> showShoppingCart() {
        return shoppingCartMapper.list(ShoppingCart.
                                                                builder().
                                                                userId(BaseContext.getCurrentId()).
                                                                build());
    }

    /**
     * 清空购物车
     */
    @Override
    public void cleanShoppingCart() {
        shoppingCartMapper.deleteByUserId(BaseContext.getCurrentId());
    }



}
