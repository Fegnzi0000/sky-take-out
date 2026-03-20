package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.SetmealDTO;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.SetmealDish;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.exception.SetmealEnableFailedException;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.SetmealService;
import com.sky.vo.DishItemVO;
import com.sky.vo.SetmealVO;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * 套餐业务实现类
 */
@Service
@Slf4j
public class SetmealServiceImpl implements SetmealService {


    @Autowired
    private SetmealMapper setmealMapper;
    @Autowired
    private SetmealDishMapper setmealDishMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private SetmealService setmealService;

    /**
     * 新增套餐，同时保存对应套餐和菜品关系
     * @param setmealDTO
     */
    @Transactional
    @Override
    public void saveWithDish(SetmealDTO setmealDTO) {
        //数据复制
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(setmealDTO,setmeal);

        //向套餐表插入数据
        setmealMapper.insert(setmeal);

        //获取生成的套餐id
        Long setmealId =setmeal.getId();

        List<SetmealDish> setmealDishes = setmealDTO.getSetmealDishes();
        setmealDishes.forEach(setmealDish -> {
            setmealDish.setDishId(setmealId);
        });

        //保存套餐的菜品的关联关系
        setmealDishMapper.insertBatch(setmealDishes);

    }

    /**
     * 分页查询
     * @param setmealPageQueryDTO
     * @return
     */
    @Override
    public PageResult pageQuery(SetmealPageQueryDTO setmealPageQueryDTO) {
        int PageNum = setmealPageQueryDTO.getPage();
        int pageSize = setmealPageQueryDTO.getPageSize();

        PageHelper.startPage(PageNum,pageSize);
        Page<SetmealVO> page = setmealMapper.pageQuery(setmealPageQueryDTO);
        return new PageResult(page.getTotal(),page.getResult());

    }

    /**
     * 套餐批量删除
     * @param ids
     */
    @Override
    public void DeleteBatch(List<Long> ids) {
        ids.forEach(id -> {
            //查询套餐是否能够删除--是否存在起售中的套餐
            Setmeal setmeal = setmealMapper.getById(id);
            if (StatusConstant.ENABLE == setmeal.getStatus()) {
                //当前套餐起售中，不能删除
                throw new DeletionNotAllowedException(MessageConstant.SETMEAL_ON_SALE);
            }
        });

        ids.forEach(id -> {
            //删除套餐表中的数据
            setmealMapper.deleteById(id);
            //删除套餐和菜品的关联关系数据
            setmealDishMapper.deleteBySetmealId(id);
        });
    }

    /**
     * 根据id查询套餐信息和对应的菜品关系
     * @param id
     * @return
     */
    @Override
    public SetmealVO getByIdWithDish(Long id) {
        Setmeal setmeal =setmealMapper.getById(id);//查询套餐基本信息
        List<SetmealDish> setemalDishes = setmealDishMapper.getBySetmealId(id);//查询套餐对应的菜品信息

        SetmealVO setmealVO = new SetmealVO();//数据复制
        BeanUtils.copyProperties(setmeal,setmealVO);//.将setmeal对象中的属性值复制到setmealVO对象中
        setmealVO.setSetmealDishes(setemalDishes);//将查询到的菜品信息设置到setmealVO对象中

        return setmealVO;//返回包含套餐信息和对应菜品信息的setmealVO对象
    }

    /**
     * 更新套餐信息，同时更新对应的菜品信息
     * @param setmealDTO
     */
    @Override
    public void update(SetmealDTO setmealDTO) {
        Setmeal setmeal = new Setmeal();//数据复制
        BeanUtils.copyProperties(setmealDTO,setmeal);//将setmealDTO对象中的属性值复制到setmeal对象中

        //1、修改套餐表，执行update
        setmealMapper.update(setmeal);

        //套餐id
        Long setmealId =setmealDTO.getId();

        //2、删除套餐和菜品的关联关系，操作setmeal_dish表，执行delete
        setmealDishMapper.deleteBySetmealId(setmealId);

        //3、获取套餐和菜品的关联关系数据，操作setmeal_dish表，执行insert
        List<SetmealDish> setmealDishes = setmealDTO.getSetmealDishes();
        setmealDishes.forEach(setmealDish -> {
            setmealDish.setSetmealId(setmealId);//将套餐id设置到每个套餐和菜品的关联关系对象中
        });

        //4、重新插入套餐和菜品的关联关系，操作setmeal_dish表，执行insert
        setmealDishMapper.insertBatch(setmealDishes);
    }

    /**
     * 起售或停售套餐
     * @param status
     * @param id
     */
    @Override
    public void startOrstop(Integer status, Long id) {
        //起售套餐时，判断套餐内是否有停售菜品，有停售菜品提示"套餐内包含未启售菜品，无法启售"
        if (status == StatusConstant.ENABLE) {
            //select a.* from dish a left join setmeal_dish b on a.id = b.dish_id where b.setmeal_id = ?
            List<Dish> dishList = dishMapper.getBySetmealId(id);
            if (dishList != null && dishList.size() > 0) {
                dishList.forEach(dish -> {
                    if (dish.getStatus() == StatusConstant.DISABLE) {
                        //套餐内包含未启售菜品，无法启售
                        throw new SetmealEnableFailedException(MessageConstant.SETMEAL_ENABLE_FAILED);
                    }
                });
            }
        }
            Setmeal setmeal = Setmeal.builder()
                    .id(id)
                    .status(status)
                    .build();
            setmealMapper.update(setmeal);

        }

    /**
     * 条件查询
     * @param setmeal
     * @return
     */
    public List<Setmeal> list(Setmeal setmeal) {
        List<Setmeal> list = setmealMapper.list(setmeal);
        return list;
    }

    /**
     * 根据id查询菜品选项
     * @param id
     * @return
     */
    public List<DishItemVO> getDishItemById(Long id) {
        return setmealMapper.getDishItemBySetmealId(id);
    }


    }

